/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.probe

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

data class OutFrame(val ptsUs: Long, val size: Int, val key: Boolean, val qp: Int, val picType: Int)

class EncodeResult(
    val frames: List<OutFrame>,
    val inputs: Int,
    val wallNs: Long,
    val format: MediaFormat?,
    val error: String?,
) {
    val reorders get() = frames.zipWithNext().count { (a, b) -> b.ptsUs < a.ptsUs }
    val keys get() = frames.count { it.key }

    fun mbps(fromPts: Long = Long.MIN_VALUE, toPts: Long = Long.MAX_VALUE): Double {
        val sel = frames.filter { it.ptsUs in fromPts until toPts }
        if (sel.size < 2) return 0.0
        val span = (sel.maxOf { it.ptsUs } - sel.minOf { it.ptsUs }).toDouble() * sel.size / (sel.size - 1)
        return sel.sumOf { it.size.toLong() } * 8 / span
    }

    val fps get() = frames.size / (wallNs / 1e9)

    fun summary() = buildString {
        if (error != null) { append("ERROR ").append(error); return@buildString }
        append("in=").append(inputs).append(" out=").append(frames.size)
        append(" fps=%.1f".format(fps)).append(" mbps=%.2f".format(mbps()))
        append(" keys=").append(keys).append(" reorders=").append(reorders)
        val qps = frames.map { it.qp }.filter { it >= 0 }
        if (qps.isNotEmpty()) append(" qp=").append(qps.min()).append('/').append("%.1f".format(qps.average())).append('/').append(qps.max())
    }
}

/**
 * Encodes [frames] of [Pattern] through the codec's input surface. Paced runs submit at [fps];
 * unpaced runs measure throughput, back-pressured by the surface queue.
 */
fun encodeSurface(
    codecName: String,
    fmt: MediaFormat,
    frames: Int,
    fps: Int,
    paced: Boolean = true,
    midRun: ((Int, MediaCodec) -> Unit)? = null,
): EncodeResult {
    val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
    val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
    val thread = HandlerThread("probe-enc").apply { start() }
    val out = ArrayList<OutFrame>(frames)
    val eos = CountDownLatch(1)
    var outFormat: MediaFormat? = null
    var error: String? = null
    val codec = try { MediaCodec.createByCodecName(codecName) } catch (e: Exception) {
        thread.quitSafely(); return EncodeResult(emptyList(), 0, 1, null, "create: $e")
    }
    codec.setCallback(object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {}
        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val f = c.getOutputFormat(index)
                out += OutFrame(
                    info.presentationTimeUs, info.size,
                    info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                    if (f.containsKey(MediaFormat.KEY_VIDEO_QP_AVERAGE)) f.getInteger(MediaFormat.KEY_VIDEO_QP_AVERAGE) else -1,
                    if (f.containsKey(MediaFormat.KEY_PICTURE_TYPE)) f.getInteger(MediaFormat.KEY_PICTURE_TYPE) else -1,
                )
            }
            c.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos.countDown()
        }
        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            error = "codec: ${e.diagnosticInfo}"
            eos.countDown()
            // Abandons the input surface, so a producer blocked in dequeueBuffer returns.
            Thread { runCatching { c.release() } }.start()
        }
        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) { outFormat = format }
    }, Handler(thread.looper))

    var submitted = 0
    var start = SystemClock.elapsedRealtimeNanos()
    try {
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val pattern = Pattern(w, h)
        val period = 1_000_000_000L / fps
        // Warm HWUI up off the clock; pacing starts at the first real frame.
        surface.lockHardwareCanvas().also { pattern.draw(it, 0); surface.unlockCanvasAndPost(it) }
        start = SystemClock.elapsedRealtimeNanos() + period
        for (i in 0 until frames) {
            if (error != null) break
            if (paced) {
                val due = start + i * period
                val wait = due - SystemClock.elapsedRealtimeNanos()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
            val canvas = surface.lockHardwareCanvas()
            pattern.draw(canvas, i)
            surface.unlockCanvasAndPost(canvas)
            submitted++
            midRun?.invoke(i, codec)
        }
        codec.signalEndOfInputStream()
        if (!eos.await(15, TimeUnit.SECONDS)) error = error ?: "EOS timeout"
        surface.release()
    } catch (e: Exception) {
        error = error ?: "run: $e"
    } finally {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        thread.quitSafely()
    }
    val wall = max(1L, SystemClock.elapsedRealtimeNanos() - start)
    // Decode order, so reorders show B-frames.
    return EncodeResult(out.toList(), submitted, wall, outFormat, error)
}

fun videoFormat(mime: String, w: Int, h: Int, fps: Int, bitrate: Int, mode: Int, gopSec: Float = 2f) =
    MediaFormat.createVideoFormat(mime, w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, 0x7f000789) // COLOR_FormatSurface
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
        setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, gopSec)
        setInteger(MediaFormat.KEY_PRIORITY, 0)
        setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
    }

/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.probe

import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
import android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * S7: throughput of the software video encoders (they run in media.swcodec; the runner samples
 * that process's CPU time around each method).
 */
@RunWith(AndroidJUnit4::class)
class S7SoftwareCodecTest {
    private fun log(line: String) = Probe.log("S7", line)

    @Test fun av1_720p() = run("c2.android.av1.encoder", MediaFormat.MIMETYPE_VIDEO_AV1, 1280, 720, 3_000_000)
    @Test fun av1_1080p() = run("c2.android.av1.encoder", MediaFormat.MIMETYPE_VIDEO_AV1, 1920, 1080, 6_000_000)
    @Test fun hevcSw_720p() = run("c2.android.hevc.encoder", MediaFormat.MIMETYPE_VIDEO_HEVC, 1280, 720, 3_000_000)
    @Test fun apv_1080p() = run("c2.android.apv.encoder", "video/apv", 1920, 1080, 60_000_000)

    private fun run(name: String, mime: String, w: Int, h: Int, bitrate: Int, frames: Int = 90) {
        val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_BITRATE_MODE, BITRATE_MODE_VBR)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 2f)
        }
        val codec = runCatching { MediaCodec.createByCodecName(name) }.getOrElse { log("$name MISSING $it"); return }
        val src = Array(4) { k -> Yuv(w, h, k) }
        val info = MediaCodec.BufferInfo()
        var queued = 0
        var outs = 0
        var bytes = 0L
        var eos = false
        val t0 = SystemClock.elapsedRealtimeNanos()
        var firstOut = 0L
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            while (!eos) {
                if (queued <= frames) {
                    val i = codec.dequeueInputBuffer(2_000)
                    if (i >= 0) {
                        if (queued == frames) {
                            codec.queueInputBuffer(i, 0, 0, queued * 33_333L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            src[queued % 4].fill(codec.getInputImage(i)!!)
                            codec.queueInputBuffer(i, 0, w * h * 3 / 2, queued * 33_333L, 0)
                        }
                        queued++
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 2_000)
                if (o >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        if (outs++ == 0) firstOut = SystemClock.elapsedRealtimeNanos() - t0
                        bytes += info.size
                    }
                    eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(o, false)
                }
                if (SystemClock.elapsedRealtimeNanos() - t0 > 120_000_000_000L) { log("$name TIMEOUT"); break }
            }
        } catch (e: Exception) {
            log("$name ${w}x$h ERROR $e"); return
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        val wall = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
        log("$name ${w}x$h frames=$outs fps=%.1f firstOutMs=%d mbpsAt30=%.2f wallS=%.1f".format(
            outs / wall, firstOut / 1_000_000, bytes * 8 / (outs / 30.0) / 1e6, wall))
    }

    /** One synthetic I420 frame: noise plus a gradient shifted by [k], copied honouring strides. */
    private class Yuv(val w: Int, val h: Int, k: Int) {
        val y = ByteArray(w * h) { i -> ((i % w + i / w + k * 37) xor (i * 2654435761L ushr 24).toInt()).toByte() }
        val u = ByteArray(w * h / 4) { i -> (i + k * 11).toByte() }
        val v = ByteArray(w * h / 4) { i -> (255 - i - k * 7).toByte() }

        fun fill(img: android.media.Image) {
            plane(img.planes[0], y, w, h)
            plane(img.planes[1], u, w / 2, h / 2)
            plane(img.planes[2], v, w / 2, h / 2)
        }

        private fun plane(p: android.media.Image.Plane, src: ByteArray, pw: Int, ph: Int) {
            val buf: ByteBuffer = p.buffer
            if (p.pixelStride == 1) {
                for (r in 0 until ph) { buf.position(r * p.rowStride); buf.put(src, r * pw, pw) }
            } else {
                for (r in 0 until ph) for (c in 0 until pw) buf.put(r * p.rowStride + c * p.pixelStride, src[r * pw + c])
            }
        }
    }
}

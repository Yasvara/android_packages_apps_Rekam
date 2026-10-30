/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.yasvara.rekam.core.EncoderPlan
import org.yasvara.rekam.core.Settings

/** Hardware HEVC or AVC through an input surface, as [plan] says; output to [Muxer] track [track]. */
internal class VideoEncoder(
    plan: EncoderPlan,
    private val muxer: Muxer,
    private val track: Int,
    private val onError: (String) -> Unit,
) {
    private val thread = HandlerThread("rk-codec").apply { start() }
    private val codec = MediaCodec.createByCodecName(Caps.videoCodec(plan.videoCodec))
    private val eos = CountDownLatch(1)
    val surface: Surface

    init {
        val avc = plan.videoCodec == Settings.VIDEO_AVC
        val f = MediaFormat.createVideoFormat(Caps.videoMime(plan.videoCodec), plan.width, plan.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, 0x7f000789) // COLOR_FormatSurface
            setInteger(MediaFormat.KEY_PROFILE, if (avc) CodecProfileLevel.AVCProfileHigh else CodecProfileLevel.HEVCProfileMain)
            setInteger(MediaFormat.KEY_BIT_RATE, plan.videoBps)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                if (plan.rateControl == Settings.RC_CBR) EncoderCapabilities.BITRATE_MODE_CBR else EncoderCapabilities.BITRATE_MODE_VBR,
            )
            plan.qp?.let { qp ->
                setInteger(MediaFormat.KEY_VIDEO_QP_MIN, qp.first)
                setInteger(MediaFormat.KEY_VIDEO_QP_MAX, qp.last)
            }
            setFloat(MediaFormat.KEY_FRAME_RATE, plan.fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, plan.keyframeSeconds)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setFloat(MediaFormat.KEY_OPERATING_RATE, plan.fps)
            // The VPU reads its rate from input timestamps and fails a session on a burst it can't
            // sustain. The mirror is already composed at the session rate, so this only guards
            // bursts: at exactly the rate, a frame a little early would be dropped.
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, plan.fps * BURST_GUARD)
            // Repeats only fill a static screen. At one frame period they would race every frame
            // that arrives a little late: the old frame goes in first and the new one lands a
            // fraction of a millisecond after it, which plays as a stutter.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, STATIC_REPEAT_US)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                c.getOutputBuffer(index)?.let { muxer.write(track, it, info) }
                c.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos.countDown()
            }

            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) = muxer.setFormat(track, format)

            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                eos.countDown()
                onError("video encoder: ${e.diagnosticInfo}")
            }
        }, Handler(thread.looper))
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
    }

    fun start() = codec.start()

    /** Paused: the encoder takes no frames. Resumed: it starts again on a key frame. */
    fun setSuspended(suspended: Boolean) = runCatching {
        codec.setParameters(Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_SUSPEND, if (suspended) 1 else 0)
            if (!suspended) putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        })
    }

    /** Flushes the last frames out; the producer (VirtualDisplay) must already be detached. */
    fun stop() {
        runCatching { codec.signalEndOfInputStream() }
        eos.await(2, TimeUnit.SECONDS)
        runCatching { codec.stop() }
        codec.release()
        surface.release()
        thread.quitSafely()
    }

    private companion object {
        const val STATIC_REPEAT_US = 100_000L
        const val BURST_GUARD = 1.5f
    }
}

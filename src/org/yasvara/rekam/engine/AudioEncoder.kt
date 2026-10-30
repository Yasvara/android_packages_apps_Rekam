/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.yasvara.rekam.core.EncoderPlan
import org.yasvara.rekam.core.Settings

/**
 * The audio track: mixed s16 stereo blocks from [AudioGraph] into AAC-LC (hardware up to
 * 192 kbps, software above), Opus or FLAC, written to [Muxer] track [track]. Blocks arrive on
 * `rk-mix` ([sink]); codec callbacks run on `rk-aenc`.
 */
internal class AudioEncoder(
    plan: EncoderPlan,
    private val muxer: Muxer,
    private val track: Int,
    private val onError: (String) -> Unit,
) {
    private val thread = HandlerThread("rk-aenc").apply { start() }
    private val codec: MediaCodec
    private val free = ArrayBlockingQueue<Int>(32)
    private val eos = CountDownLatch(1)
    @Volatile private var accepting = true
    /** Blocks dropped because the encoder had no input buffer in time. */
    val dropped = AtomicLong()

    init {
        val (name, format) = when (plan.audioCodec) {
            Settings.AUDIO_OPUS -> "c2.android.opus.encoder" to
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, AudioGraph.RATE, 2).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, plan.audioBps)
                }
            Settings.AUDIO_FLAC -> "c2.android.flac.encoder" to
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, AudioGraph.RATE, 2).apply {
                    setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
                }
            else -> (if (plan.audioHardware && Caps.hasCodec(HW_AAC)) HW_AAC else "c2.android.aac.encoder") to
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioGraph.RATE, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, plan.audioBps)
                }
        }
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AudioGraph.MIX_FRAMES * BYTES_PER_FRAME)
        codec = MediaCodec.createByCodecName(name)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
                free.offer(index)
            }

            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                c.getOutputBuffer(index)?.let { muxer.write(track, it, info) }
                c.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos.countDown()
            }

            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) = muxer.setFormat(track, format)

            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                accepting = false
                eos.countDown()
                onError("audio encoder: ${e.diagnosticInfo}")
            }
        }, Handler(thread.looper))
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start() = codec.start()

    /** Feeds one mixed block; drops it if the encoder has had no free input for 20 ms. */
    val sink = AudioGraph.Sink { pcm, frames, ptsUs ->
        if (!accepting) return@Sink
        val index = free.poll(INPUT_WAIT_MS, TimeUnit.MILLISECONDS)
        if (index == null) {
            dropped.incrementAndGet()
            return@Sink
        }
        val n = frames * BYTES_PER_FRAME
        runCatching {
            val buf = codec.getInputBuffer(index)!!
            buf.clear()
            buf.put(pcm.duplicate().apply { position(0); limit(n) })
            codec.queueInputBuffer(index, 0, n, ptsUs, 0)
        }.onFailure { if (accepting) onError("audio encoder input: $it") }
    }

    /** After the graph has detached [sink]: drains the last frames out. */
    fun stop() {
        accepting = false
        val index = free.poll(500, TimeUnit.MILLISECONDS)
        if (index != null) runCatching { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
        eos.await(2, TimeUnit.SECONDS)
        runCatching { codec.stop() }
        codec.release()
        thread.quitSafely()
    }

    private companion object {
        const val HW_AAC = "c2.qti.aac.hw.encoder"
        const val BYTES_PER_FRAME = 4 // s16 stereo
        const val INPUT_WAIT_MS = 20L
    }
}

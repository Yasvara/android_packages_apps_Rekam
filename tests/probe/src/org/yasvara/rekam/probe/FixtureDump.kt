/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.probe

import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * Real encoder output for the Rust media tests (rust/media/tests/data): `<name>.csd` holds csd-0
 * then csd-1, each as u32 length + bytes; `<name>.pkts` is u32 size, i64 ptsUs, u8 key, data.
 */
@RunWith(AndroidJUnit4::class)
class FixtureDump {
    private val dir by lazy { File(Probe.ctx.getExternalFilesDir(null), "fixtures").apply { mkdirs() } }

    @Test
    fun video() {
        for ((name, codec, mime) in listOf(
            Triple("hevc", "c2.qti.hevc.encoder", MediaFormat.MIMETYPE_VIDEO_HEVC),
            Triple("avc", "c2.qti.avc.encoder", MediaFormat.MIMETYPE_VIDEO_AVC),
        )) {
            val fmt = videoFormat(mime, 640, 360, 30, 1_000_000, BITRATE_MODE_VBR, gopSec = 1f).apply {
                if (name == "avc") setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.AVCProfileHigh)
            }
            dumpSurface(name, codec, fmt, frames = 60, fps = 30)
        }
    }

    @Test
    fun audio() {
        for ((name, codec, mime) in listOf(
            Triple("aac", "c2.android.aac.encoder", MediaFormat.MIMETYPE_AUDIO_AAC),
            Triple("opus", "c2.android.opus.encoder", MediaFormat.MIMETYPE_AUDIO_OPUS),
            Triple("flac", "c2.android.flac.encoder", MediaFormat.MIMETYPE_AUDIO_FLAC),
        )) {
            val fmt = MediaFormat.createAudioFormat(mime, 48_000, 2).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                if (name == "aac") setInteger(MediaFormat.KEY_AAC_PROFILE, CodecProfileLevel.AACObjectLC)
            }
            dumpPcm(name, codec, fmt, seconds = 2)
        }
    }

    private fun writer(name: String) = DataOutputStream(File(dir, "$name.pkts").outputStream().buffered())

    private fun writeCsd(name: String, f: MediaFormat) {
        DataOutputStream(File(dir, "$name.csd").outputStream()).use { o ->
            for (k in listOf("csd-0", "csd-1")) {
                val b = f.getByteBuffer(k) ?: continue
                val bytes = ByteArray(b.remaining()).also { b.get(it) }
                o.writeInt(bytes.size); o.write(bytes)
            }
        }
    }

    private fun packet(o: DataOutputStream, c: MediaCodec, i: Int, info: MediaCodec.BufferInfo) {
        if (info.size == 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val buf = c.getOutputBuffer(i)!!.apply { position(info.offset); limit(info.offset + info.size) }
        val bytes = ByteArray(info.size).also { buf.get(it) }
        o.writeInt(bytes.size)
        o.writeLong(info.presentationTimeUs)
        o.writeByte(if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) 1 else 0)
        o.write(bytes)
    }

    private fun dumpSurface(name: String, codecName: String, fmt: MediaFormat, frames: Int, fps: Int) {
        val out = writer(name)
        val done = CountDownLatch(1)
        val t = HandlerThread("fx").apply { start() }
        val codec = MediaCodec.createByCodecName(codecName)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, i: Int) {}
            override fun onOutputBufferAvailable(c: MediaCodec, i: Int, info: MediaCodec.BufferInfo) {
                packet(out, c, i, info)
                c.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done.countDown()
            }
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { Probe.log("FX", "$name $e"); done.countDown() }
            override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) = writeCsd(name, f)
        }, Handler(t.looper))
        codec.configure(fmt.apply { setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat()) }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val pattern = Pattern(640, 360)
        for (i in 0 until frames) {
            val cv = surface.lockHardwareCanvas(); pattern.draw(cv, i); surface.unlockCanvasAndPost(cv)
            Thread.sleep(1000L / fps)
        }
        codec.signalEndOfInputStream()
        done.await(10, TimeUnit.SECONDS)
        codec.stop(); codec.release(); surface.release(); t.quitSafely(); out.close()
        Probe.log("FX", "$name ${File(dir, "$name.pkts").length()} bytes")
    }

    private fun dumpPcm(name: String, codecName: String, fmt: MediaFormat, seconds: Int) {
        val out = writer(name)
        val codec = MediaCodec.createByCodecName(codecName)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val total = 48_000 * seconds
        var sent = 0
        var eos = false
        val info = MediaCodec.BufferInfo()
        while (!eos) {
            if (sent <= total) {
                val i = codec.dequeueInputBuffer(5_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val n = minOf((buf.capacity() / 4), total - sent)
                    for (k in 0 until n) {
                        val s = (8000 * sin(2 * PI * 440 * (sent + k) / 48_000)).toInt().toShort()
                        buf.putShort(s); buf.putShort(s)
                    }
                    val pts = sent * 1_000_000L / 48_000
                    codec.queueInputBuffer(i, 0, n * 4, pts, if (n == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    sent += if (n == 0) 1 else n
                }
            }
            val o = codec.dequeueOutputBuffer(info, 5_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) writeCsd(name, codec.outputFormat)
            if (o >= 0) {
                packet(out, codec, o, info)
                eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(o, false)
            }
        }
        codec.stop(); codec.release(); out.close()
        Probe.log("FX", "$name ${File(dir, "$name.pkts").length()} bytes")
    }
}

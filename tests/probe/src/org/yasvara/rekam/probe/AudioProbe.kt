/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.probe

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared by the audio spikes: a quiet test tone and a 1 s capture measurement. */
object AudioProbe {
    val FORMAT: AudioFormat = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(48_000)
        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()

    /** 1 s of capture: RMS in dBFS, whether MONOTONIC timestamps work, and read sizes. */
    fun measure(r: AudioRecord): String {
        val buf = FloatArray(960) // 10 ms stereo
        r.startRecording()
        SystemClock.sleep(200)
        var sum = 0.0
        var n = 0L
        val t0 = SystemClock.elapsedRealtime()
        var shortReads = 0
        while (SystemClock.elapsedRealtime() - t0 < 1000) {
            val got = r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (got < buf.size) shortReads++
            for (i in 0 until maxOf(got, 0)) { sum += buf[i] * buf[i]; n++ }
        }
        val ts = AudioTimestamp()
        val tsOk = r.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
        r.stop()
        val rms = if (n > 0) sqrt(sum / n) else 0.0
        return "rmsDb=%.1f samples=%d shortReads=%d tsMonotonic=%b rate=%d".format(
            if (rms > 0) 20 * log10(rms) else -999.0, n, shortReads, tsOk, r.sampleRate)
    }

    /** Quiet 1 kHz sine (−30 dBFS) so the phone isn't loud while probing. */
    class Tone(usage: Int) {
        private val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .build()
        @Volatile private var running = false
        private var t: Thread? = null

        fun start() {
            running = true
            track.play()
            t = thread {
                val buf = FloatArray(960)
                var ph = 0.0
                while (running) {
                    for (i in 0 until 480) {
                        val s = (0.0316 * sin(ph)).toFloat(); ph += 2 * PI * 1000 / 48_000
                        buf[2 * i] = s; buf[2 * i + 1] = s
                    }
                    track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                }
            }
            SystemClock.sleep(200)
        }

        fun stop() { running = false; t?.join(500); track.stop(); track.release() }
    }
}

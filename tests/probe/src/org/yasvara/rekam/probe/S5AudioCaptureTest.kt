/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.probe

import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

/** S5: per-uid playback capture count, voice-comm capture, and whether Swara gain reaches the capture. */
@RunWith(AndroidJUnit4::class)
class S5AudioCaptureTest {
    @Before fun up() = Proj.startFgs()
    @After fun down() = Proj.stopFgs()

    private fun log(line: String) = Probe.log("S5", line)

    @Test
    fun perUidConcurrent() {
        val tone = AudioProbe.Tone(AudioAttributes.USAGE_GAME).apply { start() }
        val p = Proj.create()
        try {
            val recs = (1..4).map { capture(p) { addMatchingUid(Process.myUid()); addMatchingUsage(AudioAttributes.USAGE_GAME) } } +
                listOf(capture(p) { addMatchingUsage(AudioAttributes.USAGE_GAME); excludeUid(Process.myUid()) })
            val results = arrayOfNulls<String>(recs.size)
            recs.mapIndexed { i, r -> thread { results[i] = r?.let { AudioProbe.measure(it) } ?: "build failed" } }.forEach { it.join() }
            results.forEachIndexed { i, s -> log("perUid rec$i ${if (i == 4) "(excludes self)" else ""} $s") }
            recs.forEach { it?.release() }
        } finally { p.stop(); tone.stop() }
    }

    @Test
    fun voiceComm() {
        val tone = AudioProbe.Tone(AudioAttributes.USAGE_VOICE_COMMUNICATION).apply { start() }
        val p = Proj.create()
        try {
            val r = capture(p) { addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) }
            log("voiceComm ${r?.let { AudioProbe.measure(it) } ?: "build failed"}")
            r?.release()
        } finally { p.stop(); tone.stop() }
    }

    @Test
    fun swaraGainOnCapture() {
        val pkg = Probe.ctx.packageName
        val tone = AudioProbe.Tone(AudioAttributes.USAGE_GAME).apply { start() }
        val p = Proj.create()
        try {
            val r = capture(p) { addMatchingUid(Process.myUid()); addMatchingUsage(AudioAttributes.USAGE_GAME) }!!
            log("swara volume=1.0 ${AudioProbe.measure(r)}")
            log("swara set: ${Proj.shell("cmd yasvara_swara set $pkg volume 0.25")}")
            SystemClock.sleep(300)
            log("swara volume=0.25 ${AudioProbe.measure(r)}")
            Proj.shell("cmd yasvara_swara set $pkg mute on")
            SystemClock.sleep(300)
            log("swara muted ${AudioProbe.measure(r)}")
            r.release()
        } finally {
            Proj.shell("cmd yasvara_swara reset $pkg")
            p.stop(); tone.stop()
        }
    }

    /** Does the Swara rule reach the real output track? Reads AudioFlinger's per-track volume. */
    @Test
    fun swaraSpeakerTrack() {
        val pkg = Probe.ctx.packageName
        val uid = Process.myUid()
        val tone = AudioProbe.Tone(AudioAttributes.USAGE_GAME).apply { start() }
        try {
            fun tracks() = Proj.shell("dumpsys media.audio_flinger").lines()
                .filter { it.contains(" $uid ") || it.contains("uid $uid") || it.contains("Swara") || it.contains("swara") }
                .take(6).joinToString(" | ") { it.trim().replace(Regex("\\s+"), " ") }
            log("speaker before: ${tracks()}")
            Proj.shell("cmd yasvara_swara set $pkg volume 0.25")
            SystemClock.sleep(400)
            log("speaker volume=0.25: ${tracks()}")
            log("swara dump: ${Proj.shell("cmd yasvara_swara dump").lines().filter { it.contains(pkg) || it.contains(uid.toString()) }.joinToString(" | ")}")
        } finally {
            Proj.shell("cmd yasvara_swara reset $pkg")
            tone.stop()
        }
    }

    private fun capture(p: MediaProjection, cfg: AudioPlaybackCaptureConfiguration.Builder.() -> Unit): AudioRecord? = runCatching {
        AudioRecord.Builder().setAudioFormat(AudioProbe.FORMAT).setBufferSizeInBytes(48_000 * 8 / 5)
            .setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(p).apply(cfg).build()).build()
    }.onFailure { log("capture build: $it") }.getOrNull()
}

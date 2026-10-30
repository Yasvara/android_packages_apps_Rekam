/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.probe

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiopolicy.AudioMix
import android.media.audiopolicy.AudioMixingRule
import android.media.audiopolicy.AudioPolicy
import android.os.Process
import android.util.Pair
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S10: playback capture through Rekam's own AudioPolicy, with MODIFY_AUDIO_ROUTING and no
 * MediaProjection: does it capture, does an excluding mix still deliver a steady silent clock,
 * and can the rules change on a live mix?
 */
@RunWith(AndroidJUnit4::class)
class S10PolicyCaptureTest {
    @Before fun up() = Proj.startFgs()
    @After fun down() = Proj.stopFgs()

    private fun log(line: String) = Probe.log("S10", line)
    private val am get() = Probe.ctx.getSystemService(AudioManager::class.java)
    private val self = Process.myUid()

    private fun rule(uid: Int, match: Boolean): AudioMixingRule = AudioMixingRule.Builder().apply {
        setTargetMixRole(AudioMixingRule.MIX_ROLE_PLAYERS)
        for (u in intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)) {
            addRule(AudioAttributes.Builder().setUsage(u).build(), AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE)
        }
        if (match) addMixRule(AudioMixingRule.RULE_MATCH_UID, uid) else excludeMixRule(AudioMixingRule.RULE_MATCH_UID, uid)
    }.build()

    private class Capture(val policy: AudioPolicy, val mix: AudioMix, val record: AudioRecord)

    private fun open(rule: AudioMixingRule): Capture? = runCatching {
        val mix = AudioMix.Builder(rule).setFormat(AudioProbe.FORMAT)
            .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK or AudioMix.ROUTE_FLAG_RENDER).build()
        val policy = AudioPolicy.Builder(Probe.ctx).addMix(mix).build()
        check(am.registerAudioPolicy(policy) == AudioManager.SUCCESS) { "registerAudioPolicy failed" }
        val record = checkNotNull(policy.createAudioRecordSink(mix)) { "no sink" }
        Capture(policy, mix, record)
    }.onFailure { log("open: $it") }.getOrNull()

    private fun Capture.close() {
        record.release()
        am.unregisterAudioPolicy(policy)
    }

    @Test
    fun matchSelf() {
        val tone = AudioProbe.Tone(AudioAttributes.USAGE_GAME).apply { start() }
        try {
            val c = open(rule(self, match = true))
            log("match self (tone -30 dBFS): ${c?.let { AudioProbe.measure(it.record) } ?: "failed"}")
            c?.close()
        } finally { tone.stop() }
    }

    @Test
    fun excludingMixIsASteadyClock() {
        val tone = AudioProbe.Tone(AudioAttributes.USAGE_GAME).apply { start() }
        try {
            val others = open(rule(self, match = false))
            log("exclude self while only self plays: ${others?.let { AudioProbe.measure(it.record) } ?: "failed"}")
            others?.close()
            val idle = open(rule(self, match = false))
            tone.stop()
            log("exclude self, nothing playing: ${idle?.let { AudioProbe.measure(it.record) } ?: "failed"}")
            idle?.close()
        } finally { runCatching { tone.stop() } }
    }

    @Test
    fun concurrentAppAndOthers() {
        val tone = AudioProbe.Tone(AudioAttributes.USAGE_GAME).apply { start() }
        try {
            val app = open(rule(self, match = true))
            val others = open(rule(self, match = false))
            val out = arrayOfNulls<String>(2)
            listOf(app, others).mapIndexed { i, c -> kotlin.concurrent.thread { out[i] = c?.let { AudioProbe.measure(it.record) } } }
                .forEach { it.join() }
            log("concurrent app=${out[0]} | others=${out[1]}")
            app?.close(); others?.close()
        } finally { tone.stop() }
    }

    @Test
    fun updateRulesLive() {
        val tone = AudioProbe.Tone(AudioAttributes.USAGE_GAME).apply { start() }
        try {
            val c = open(rule(self, match = false)) ?: return
            log("before update (excludes self): ${AudioProbe.measure(c.record)}")
            val status = c.policy.updateMixingRules(listOf(Pair(c.mix, rule(self, match = true))))
            log("updateMixingRules status=$status")
            log("after update (matches self): ${AudioProbe.measure(c.record)}")
            c.close()
        } finally { tone.stop() }
    }
}

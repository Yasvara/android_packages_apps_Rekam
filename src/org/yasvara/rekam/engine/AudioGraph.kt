/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.audiopolicy.AudioMix
import android.media.audiopolicy.AudioMixingRule
import android.media.audiopolicy.AudioPolicy
import android.os.Handler
import android.os.Process
import android.util.Log
import android.util.Pair
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.yasvara.rekam.AudioSource
import org.yasvara.rekam.core.AudioClock
import org.yasvara.rekam.core.SourcePlan

/**
 * Rekam's audio mixer. Every playback source is captured through Rekam's own audio policy
 * (MODIFY_AUDIO_ROUTING, no MediaProjection), so the graph runs before a recording starts and its
 * meters are live whenever a UI watches.
 *
 * Strips: Desktop Audio ([SourcePlan.DESKTOP], every app without its own strip, and the master
 * clock), Mic/Aux ([SourcePlan.MIC], captured only while unmuted), one per app that plays (split
 * out of Desktop Audio until the user merges it back), and the master. Every block (1024 frames,
 * one AAC frame) is mixed in native, metered into the page, and handed to the [Sink] with its PTS.
 *
 * Threads: control calls on `rk-ctl`; capture on `rk-audio-<slot>`; mixing on `rk-mix`.
 */
internal class AudioGraph(
    private val context: Context,
    private val settings: MixerSettings,
    /** The meter page (shared memory, mapped for the life of the process). */
    page: ByteBuffer,
    private val ctl: Handler,
    private val onSources: () -> Unit,
    private val onError: (String) -> Unit,
) {
    /** Receives every mixed block on `rk-mix`; [pcm] is s16 stereo, valid during the call. */
    fun interface Sink {
        fun onBlock(pcm: ByteBuffer, frames: Int, ptsUs: Long)
    }

    private class Capture(val record: AudioRecord, val policy: AudioPolicy?, val mix: AudioMix?, val mono: Boolean) {
        var thread: Thread? = null
        /** Cleared before the record stops, so its last read is not an error. */
        @Volatile var live = true
    }

    private val mixer = Native.mixerNew(SLOTS)
    private val plan = SourcePlan(SLOTS, Process.myUid())
    /** Strip state by slot; [captures] holds what is actually open. */
    private val strips = arrayOfNulls<AudioSource>(SLOTS)
    private val captures = arrayOfNulls<Capture>(SLOTS)
    private val masterStrip = settings.load(source(ID_MASTER, AudioSource.KIND_MASTER, MASTER_SLOT))
    private val am = context.getSystemService(AudioManager::class.java)
    /** Mutes for this recording only (single app: every other strip), not the user's own. */
    private val sessionMutes = HashSet<String>()
    /** The app a single-app recording is about: it always has a strip; later ones start muted. */
    private var focusPkg: String? = null
    @Volatile private var sink: Sink? = null
    /** Desktop Audio's capture: its clock times every block. */
    @Volatile private var clockRecord: AudioRecord? = null
    @Volatile private var running = false
    private var mixThread: Thread? = null

    private val format = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setSampleRate(RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
        .build()

    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) = addPlaying(configs)
    }

    init {
        check(Native.mixerAttachMeters(mixer, page)) { "meter page" }
    }

    /**
     * Desktop Audio, the apps, Mic/Aux, then the master, as copies: [AudioSource.muted] is what
     * the mix does, including a strip held muted for a single-app recording.
     */
    val sources: List<AudioSource>
        get() = (listOfNotNull(strips[SourcePlan.DESKTOP]) +
            (SourcePlan.FIRST_APP_SLOT until SLOTS).mapNotNull { strips[it] } +
            listOfNotNull(strips[SourcePlan.MIC], masterStrip))
            .map { s -> source(s.id, s.kind, s.slot).also { it.packageName = s.packageName; it.copyFrom(s); it.muted = s.muted || s.id in sessionMutes } }

    /** Mic/Aux is open (unmuted): a recording needs the microphone foreground type. */
    val micOpen: Boolean get() = captures[SourcePlan.MIC] != null

    fun start() {
        if (running) return
        running = true
        strips[SourcePlan.DESKTOP] = settings.load(source(ID_DESKTOP, AudioSource.KIND_DESKTOP, SourcePlan.DESKTOP))
        strips[SourcePlan.MIC] = settings.load(source(ID_MIC, AudioSource.KIND_MIC, SourcePlan.MIC))
        applyMaster()
        open(SourcePlan.DESKTOP, desktopRule())
        if (!strips[SourcePlan.MIC]!!.muted) open(SourcePlan.MIC, null)
        addPlaying(am.activePlaybackConfigurations)
        am.registerAudioPlaybackCallback(playback, ctl)
        mixThread = Thread(::mix, "rk-mix").apply { start() }
        onSources()
    }

    /** Applies [s]'s fader, filters and mute to the strip with its id, and keeps them. */
    fun setSource(s: AudioSource) {
        if (s.id == ID_MASTER) {
            masterStrip.copyFrom(settings.save(s))
            applyMaster()
            onSources()
            return
        }
        val slot = strips.indexOfFirst { it?.id == s.id }
        if (slot < 0) return
        val strip = strips[slot]!!
        strip.copyFrom(settings.save(s))
        sessionMutes -= strip.id
        apply(strip)
        if (slot == SourcePlan.MIC) {
            if (!strip.muted && captures[slot] == null) open(slot, null)
            if (strip.muted && captures[slot] != null) close(slot)
        }
        onSources()
    }

    /** Opens or closes Mic/Aux as its mute says; remembered like any strip change. */
    fun setMicOpen(open: Boolean) {
        strips[SourcePlan.MIC]?.let { mic -> setSource(AudioSource().apply { copyFrom(mic); id = mic.id; muted = !open }) }
    }

    /** Merges app strip [id] back into Desktop Audio, for good. */
    fun removeSource(id: String) {
        val slot = strips.indexOfFirst { it?.id == id && it.kind == AudioSource.KIND_APP }
        if (slot < 0) return
        val pkg = strips[slot]!!.packageName
        if (pkg != null) {
            if (pkg == focusPkg) return // the recorded app keeps its strip
            settings.setMerged(pkg, true)
        }
        val uid = plan.appSlots.entries.firstOrNull { it.value == slot }?.key ?: return
        close(slot)
        strips[slot] = null
        plan.remove(uid)
        updateDesktop()
        onSources()
    }

    /** Gives [pkg] its strip now, whether or not it is playing (the recorded app). */
    fun addApp(pkg: String) {
        val uid = runCatching { context.packageManager.getPackageUid(pkg, 0) }.getOrNull() ?: return
        addUids(listOf(uid))
    }

    /**
     * For a recording of [pkg] alone: every other playback strip starts muted for this recording,
     * without touching the user's saved mutes. Null clears it.
     */
    fun focus(pkg: String?) {
        focusPkg = pkg
        sessionMutes.clear()
        if (pkg != null) {
            sessionMutes += strips.filterNotNull()
                .filter { it.kind == AudioSource.KIND_DESKTOP || it.kind == AudioSource.KIND_APP && it.packageName != pkg }
                .map { it.id }
        }
        strips.filterNotNull().forEach(::apply)
        onSources()
    }

    fun attach(s: Sink?) {
        sink = s
    }

    fun stop() {
        if (!running) return
        running = false
        am.unregisterAudioPlaybackCallback(playback)
        // The mixer reads Desktop Audio's clock: it stops before any capture is released.
        mixThread?.join(1000)
        mixThread = null
        captures.indices.forEach(::close)
        strips.fill(null)
        plan.clear()
        Native.mixerFree(mixer)
    }

    private fun addPlaying(configs: List<AudioPlaybackConfiguration>) {
        if (!running) return
        addUids(configs.filter { it.isActive && it.audioAttributes.usage in CAPTURED_USAGES }.map { it.clientUid })
    }

    private fun addUids(uids: List<Int>) {
        val pm = context.packageManager
        val wanted = uids.filter { uid ->
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
            pkg == null || pkg == focusPkg || !settings.merged(pkg)
        }
        val added = plan.add(wanted)
        if (added.isEmpty()) return
        for ((uid, slot) in added) {
            // The uid is taken out of Desktop Audio below: it gets a strip even when unnamed.
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
            val strip = settings.load(source(pkg?.let(::appId) ?: "uid:$uid", AudioSource.KIND_APP, slot).apply { packageName = pkg })
            if (pkg != null && pkg == focusPkg) settings.setMerged(pkg, false)
            if (focusPkg != null && pkg != focusPkg) sessionMutes += strip.id
            strips[slot] = strip
            open(slot, appRule(uid))
            if (captures[slot] == null) {
                strips[slot] = null
                plan.remove(uid)
            }
        }
        updateDesktop()
        onSources()
    }

    /** Desktop Audio stops capturing what now has its own strip: each app is heard once. */
    private fun updateDesktop() {
        val c = captures[SourcePlan.DESKTOP] ?: return
        val status = c.policy!!.updateMixingRules(listOf(Pair(c.mix!!, desktopRule())))
        if (status != AudioManager.SUCCESS) Log.w(TAG, "desktop mix update: $status")
    }

    private fun desktopRule() = playbackRule { for (uid in plan.excluded) excludeMixRule(AudioMixingRule.RULE_MATCH_UID, uid) }

    private fun appRule(uid: Int) = playbackRule { addMixRule(AudioMixingRule.RULE_MATCH_UID, uid) }

    /** Media, game and unknown playback, narrowed by [by]. */
    private fun playbackRule(by: AudioMixingRule.Builder.() -> Unit): AudioMixingRule = AudioMixingRule.Builder().apply {
        setTargetMixRole(AudioMixingRule.MIX_ROLE_PLAYERS)
        for (u in CAPTURED_USAGES) addRule(AudioAttributes.Builder().setUsage(u).build(), AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE)
        by()
    }.build()

    /** Opens [slot]'s capture: playback through a policy mix for [rule], else the microphone. */
    @SuppressLint("MissingPermission")
    private fun open(slot: Int, rule: AudioMixingRule?) {
        val id = strips[slot]?.id
        val c = runCatching {
            if (rule != null) {
                val mix = AudioMix.Builder(rule).setFormat(format)
                    .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK or AudioMix.ROUTE_FLAG_RENDER).build()
                val policy = AudioPolicy.Builder(context).addMix(mix).build()
                check(am.registerAudioPolicy(policy) == AudioManager.SUCCESS) { "audio policy refused" }
                val record = policy.createAudioRecordSink(mix)
                if (record == null) {
                    am.unregisterAudioPolicy(policy)
                    error("no capture for the mix")
                }
                Capture(record, policy, mix, mono = false)
            } else {
                micCapture()
            }
        }.onFailure { Log.w(TAG, "source $id unavailable", it) }.getOrNull() ?: return
        captures[slot] = c
        if (slot == SourcePlan.DESKTOP) clockRecord = c.record
        strips[slot]?.let(::apply)
        Native.mixerActive(mixer, slot, true)
        c.record.startRecording()
        c.thread = Thread({ capture(slot, c) }, "rk-audio-$slot").apply { start() }
    }

    @SuppressLint("MissingPermission")
    private fun micCapture(): Capture {
        fun build(mask: Int) = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(RATE).setChannelMask(mask).build())
            .setBufferSizeInBytes(RATE * FRAME_BYTES / 5)
            .build()
        val stereo = runCatching { build(AudioFormat.CHANNEL_IN_STEREO) }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        val record = stereo ?: build(AudioFormat.CHANNEL_IN_MONO)
        check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone busy" }
        return Capture(record, null, null, mono = stereo == null)
    }

    private fun close(slot: Int) {
        val c = captures[slot] ?: return
        captures[slot] = null
        c.live = false
        runCatching { c.record.stop() }
        c.thread?.join(1000)
        c.record.release()
        c.policy?.let(am::unregisterAudioPolicy)
        Native.mixerActive(mixer, slot, false)
    }

    private fun apply(s: AudioSource) {
        Native.mixerGain(mixer, s.slot, s.volumeDb + s.boostDb)
        Native.mixerMute(mixer, s.slot, s.muted || s.id in sessionMutes)
        Native.mixerShape(mixer, s.slot, s.mono, s.balance)
    }

    private fun applyMaster() = Native.mixerMaster(mixer, masterStrip.volumeDb + masterStrip.boostDb)

    private fun capture(slot: Int, c: Capture) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val bytes = BLOCK_FRAMES * FRAME_BYTES
        val buf = ByteBuffer.allocateDirect(if (c.mono) bytes / 2 else bytes).order(ByteOrder.nativeOrder())
        val wide = if (c.mono) ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()) else buf
        while (running && c.live) {
            val n = c.record.read(buf, buf.capacity(), AudioRecord.READ_BLOCKING)
            when {
                n > 0 && c.mono -> {
                    val src = buf.asFloatBuffer()
                    val dst = wide.asFloatBuffer()
                    for (i in 0 until n / 4) {
                        val v = src.get(i)
                        dst.put(2 * i, v)
                        dst.put(2 * i + 1, v)
                    }
                    Native.mixerPush(mixer, slot, wide, n * 2)
                }
                n > 0 -> Native.mixerPush(mixer, slot, buf, n)
                n < 0 -> {
                    if (running && c.live) onError("audio source ${strips[slot]?.id}: read $n")
                    return
                }
            }
        }
    }

    /** Pulls blocks while running; PTS from Desktop Audio's capture clock (CLOCK_MONOTONIC). */
    private fun mix() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val out = ByteBuffer.allocateDirect(MIX_FRAMES * 4).order(ByteOrder.nativeOrder())
        val clock = AudioClock(RATE)
        val ts = AudioTimestamp()
        var frames = 0L
        while (running) {
            if (!Native.mixerPull(mixer, out, 0, MIX_FRAMES * 4, PULL_TIMEOUT_MS)) continue
            val record = clockRecord
            if (record != null && (!clock.anchored || frames % RATE < MIX_FRAMES) &&
                record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
            ) {
                clock.anchor(ts.framePosition, ts.nanoTime)
            }
            val pts = clock.ptsUs(frames)
            frames += MIX_FRAMES
            if (pts != null) sink?.onBlock(out, MIX_FRAMES, pts)
        }
    }

    private fun AudioSource.copyFrom(o: AudioSource) {
        volumeDb = o.volumeDb
        boostDb = o.boostDb
        muted = o.muted
        mono = o.mono
        balance = o.balance
    }

    companion object {
        private const val TAG = "Rekam"
        const val RATE = 48_000
        const val SLOTS = 16
        /** The master's place in the meter page, after every slot. */
        const val MASTER_SLOT = SLOTS
        /** One AAC frame. */
        const val MIX_FRAMES = 1024
        private const val FRAME_BYTES = 8 // stereo f32
        private const val BLOCK_FRAMES = 480
        private const val PULL_TIMEOUT_MS = 100
        const val ID_DESKTOP = "desktop"
        const val ID_MIC = "mic"
        const val ID_MASTER = "master"
        fun appId(pkg: String) = "app:$pkg"
        private fun source(id: String, kind: Int, slot: Int) = AudioSource().also {
            it.id = id
            it.kind = kind
            it.slot = slot
        }
        private val CAPTURED_USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
    }
}

/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.engine

import android.content.Context
import org.yasvara.rekam.AudioSource
import org.yasvara.rekam.RecordRequest

/**
 * What the engine remembers across recordings: each strip's fader, filters and mute (by
 * [AudioSource.id]), the apps merged back into Desktop Audio, and the last settings, which the
 * tile and Pentas start with. Engine process only.
 */
internal class MixerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("mixer", Context.MODE_PRIVATE)

    /** [s] with its saved values; the microphone starts muted until the user opens it. */
    fun load(s: AudioSource): AudioSource = s.apply {
        volumeDb = prefs.getFloat("volume/$id", 0f)
        boostDb = prefs.getFloat("boost/$id", 0f)
        muted = prefs.getBoolean("muted/$id", kind == AudioSource.KIND_MIC)
        mono = prefs.getBoolean("mono/$id", false)
        balance = prefs.getFloat("balance/$id", 0f)
    }

    /** Saves [s], clamped to what a strip can do; returns what was saved. */
    fun save(s: AudioSource): AudioSource {
        s.volumeDb = s.volumeDb.finite(0f).coerceIn(MIN_DB, 0f)
        s.boostDb = s.boostDb.finite(0f).coerceIn(0f, MAX_BOOST_DB)
        s.balance = s.balance.finite(0f).coerceIn(-1f, 1f)
        prefs.edit()
            .putFloat("volume/${s.id}", s.volumeDb)
            .putFloat("boost/${s.id}", s.boostDb)
            .putBoolean("muted/${s.id}", s.muted)
            .putBoolean("mono/${s.id}", s.mono)
            .putFloat("balance/${s.id}", s.balance)
            .apply()
        return s
    }

    fun setMuted(id: String, muted: Boolean) = prefs.edit().putBoolean("muted/$id", muted).apply()

    /** Apps the user merged back into Desktop Audio. */
    fun merged(pkg: String): Boolean = prefs.getBoolean("merged/$pkg", false)

    fun setMerged(pkg: String, merged: Boolean) = prefs.edit().putBoolean("merged/$pkg", merged).apply()

    /** The last request's quality and mic, as a template (no target). */
    fun last(): RecordRequest = RecordRequest().apply {
        mic = !prefs.getBoolean("muted/${AudioGraph.ID_MIC}", true)
        preset = prefs.getInt("last/preset", preset)
        videoCodec = prefs.getInt("last/videoCodec", videoCodec)
        rateControl = prefs.getInt("last/rateControl", rateControl)
        videoKbps = prefs.getInt("last/videoKbps", videoKbps)
        shortSide = prefs.getInt("last/shortSide", shortSide)
        fps = prefs.getInt("last/fps", fps)
        keyframeMs = prefs.getInt("last/keyframeMs", keyframeMs)
        audioCodec = prefs.getInt("last/audioCodec", audioCodec)
        audioKbps = prefs.getInt("last/audioKbps", audioKbps)
    }

    fun setLast(r: RecordRequest) = prefs.edit()
        .putBoolean("muted/${AudioGraph.ID_MIC}", !r.mic)
        .putInt("last/preset", r.preset)
        .putInt("last/videoCodec", r.videoCodec)
        .putInt("last/rateControl", r.rateControl)
        .putInt("last/videoKbps", r.videoKbps)
        .putInt("last/shortSide", r.shortSide)
        .putInt("last/fps", r.fps)
        .putInt("last/keyframeMs", r.keyframeMs)
        .putInt("last/audioCodec", r.audioCodec)
        .putInt("last/audioKbps", r.audioKbps)
        .apply()

    /** [r] with its quality and mic from [last] when it asks for that. */
    fun complete(r: RecordRequest): RecordRequest = if (!r.useLast) r else last().also {
        it.packageName = r.packageName
        it.taskId = r.taskId
    }

    private fun Float.finite(default: Float) = if (isFinite()) this else default

    companion object {
        const val MIN_DB = -60f
        const val MAX_BOOST_DB = 30f
    }
}

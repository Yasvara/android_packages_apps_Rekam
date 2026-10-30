/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.core

/**
 * Pause without a gap: samples captured while paused are dropped, later ones are shifted back by
 * the total time spent paused, so the file is one continuous timeline. Times in µs on the capture
 * clock. Thread-safe.
 */
class PauseClock {
    private val lock = Any()
    private var pausedAtUs = -1L
    /** Closed pauses: start → length, in order. */
    private val gaps = ArrayList<LongArray>()

    val paused: Boolean get() = synchronized(lock) { pausedAtUs >= 0 }

    /** Total paused time so far, including a pause still open at [nowUs]. */
    fun pausedUs(nowUs: Long): Long = synchronized(lock) {
        gaps.sumOf { it[1] } + if (pausedAtUs >= 0) (nowUs - pausedAtUs).coerceAtLeast(0) else 0
    }

    fun pause(nowUs: Long) = synchronized(lock) {
        if (pausedAtUs < 0) pausedAtUs = nowUs
    }

    fun resume(nowUs: Long) = synchronized(lock) {
        if (pausedAtUs < 0) return
        if (nowUs > pausedAtUs) gaps += longArrayOf(pausedAtUs, nowUs - pausedAtUs)
        pausedAtUs = -1
    }

    /**
     * Where a sample captured at [ptsUs] goes in the file, or null if it was captured while
     * paused. Samples from before a pause keep their time.
     */
    fun map(ptsUs: Long): Long? = synchronized(lock) {
        if (pausedAtUs in 0..ptsUs) return null
        var shift = 0L
        for (g in gaps) {
            if (ptsUs < g[0]) break
            if (ptsUs < g[0] + g[1]) return null
            shift += g[1]
        }
        ptsUs - shift
    }
}

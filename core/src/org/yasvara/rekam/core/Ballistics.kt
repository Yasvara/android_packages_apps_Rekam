/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.core

import kotlin.math.max

/**
 * A meter needle, as broadcast meters move: instant attack, a linear fall of [decayDbPerS],
 * and a peak-hold tick that stays [holdNs] before it falls too. Values in dBFS.
 */
class Ballistics(
    private val decayDbPerS: Float = 24f,
    private val holdNs: Long = 1_500_000_000L,
    private val floor: Float = FLOOR_DB,
) {
    var peak = floor
        private set
    var rms = floor
        private set
    var hold = floor
        private set
    private var holdAt = 0L
    private var lastNs = Long.MIN_VALUE

    /** Feeds the levels seen at [nowNs] (or nothing new: pass the floor). */
    fun update(nowNs: Long, peakIn: Float, rmsIn: Float) {
        val dt = if (lastNs == Long.MIN_VALUE) 0L else (nowNs - lastNs).coerceAtLeast(0)
        lastNs = nowNs
        val fall = decayDbPerS * dt / 1e9f
        peak = max(peakIn, peak - fall).coerceAtLeast(floor)
        rms = max(rmsIn, rms - fall).coerceAtLeast(floor)
        if (peak >= hold) {
            hold = peak
            holdAt = nowNs
        } else if (nowNs - holdAt > holdNs) {
            hold = max(peak, hold - fall)
        }
    }

    fun reset() {
        peak = floor
        rms = floor
        hold = floor
        lastNs = Long.MIN_VALUE
    }

    companion object {
        const val FLOOR_DB = -100f
    }
}

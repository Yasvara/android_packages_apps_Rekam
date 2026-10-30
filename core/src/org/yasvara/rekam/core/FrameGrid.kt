/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.core

import kotlin.math.roundToLong

/**
 * Puts video frames on the session's frame grid. The mirror is composed once per grid period, but
 * its timestamps carry a millisecond or two of scheduling jitter, which a player shows as uneven
 * motion. Each frame moves to the nearest grid point after the previous frame's; a skipped
 * composition keeps its gap, and two frames that land in one slot go to consecutive slots.
 * Times in µs; input must not run backwards.
 */
class FrameGrid(fps: Float) {
    private val periodUs = 1_000_000.0 / fps
    private var originUs = Long.MIN_VALUE
    private var lastSlot = 0L

    fun snap(ptsUs: Long): Long {
        if (originUs == Long.MIN_VALUE) {
            originUs = ptsUs
            return ptsUs
        }
        val slot = maxOf(((ptsUs - originUs) / periodUs).roundToLong(), lastSlot + 1)
        lastSlot = slot
        return originUs + (slot * periodUs).roundToLong()
    }
}

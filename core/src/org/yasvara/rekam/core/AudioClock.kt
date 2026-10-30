/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.core

/**
 * Presentation times for mixed audio blocks on the master capture's clock (CLOCK_MONOTONIC, the
 * clock video timestamps use too). Each anchor maps a frame position to a time; between anchors
 * time advances by the sample count. Re-anchoring never steps backwards, so the PTS stays
 * monotonic while it follows the capture clock's drift.
 */
class AudioClock(private val rate: Int) {
    private var anchorNs = 0L
    private var anchorFrame = 0L
    private var lastUs = Long.MIN_VALUE

    val anchored: Boolean get() = anchorNs != 0L

    /** A capture timestamp: frame [framePosition] was captured at [nanoTime]. */
    fun anchor(framePosition: Long, nanoTime: Long) {
        if (nanoTime <= 0) return
        anchorNs = nanoTime
        anchorFrame = framePosition
    }

    /** PTS in µs of the block that starts at [frame]; null until the first anchor. */
    fun ptsUs(frame: Long): Long? {
        if (!anchored) return null
        val us = (anchorNs + (frame - anchorFrame) * 1_000_000_000L / rate) / 1000
        // A re-anchor can land a few µs behind the previous block; hold the line instead.
        val out = if (lastUs == Long.MIN_VALUE) us else maxOf(us, lastUs + 1)
        lastUs = out
        return out
    }
}

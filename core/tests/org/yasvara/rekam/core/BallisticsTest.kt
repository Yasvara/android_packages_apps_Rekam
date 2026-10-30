/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BallisticsTest {
    private val ms = 1_000_000L

    @Test fun attackIsInstantAndDecayIsLinear() {
        val b = Ballistics(decayDbPerS = 24f, holdNs = 1_000 * ms)
        b.update(0, -6f, -12f)
        assertEquals(-6f, b.peak)
        assertEquals(-12f, b.rms)
        b.update(500 * ms, Ballistics.FLOOR_DB, Ballistics.FLOOR_DB)
        assertEquals(-18f, b.peak, 1e-3f)
        assertEquals(-24f, b.rms, 1e-3f)
        b.update(600 * ms, -3f, -40f)
        assertEquals(-3f, b.peak)
        assertEquals(-26.4f, b.rms, 1e-3f)
    }

    @Test fun holdStaysThenFalls() {
        val b = Ballistics(decayDbPerS = 20f, holdNs = 1_000 * ms)
        b.update(0, -1f, -10f)
        b.update(900 * ms, Ballistics.FLOOR_DB, Ballistics.FLOOR_DB)
        assertEquals("held", -1f, b.hold)
        b.update(1_500 * ms, Ballistics.FLOOR_DB, Ballistics.FLOOR_DB)
        assertEquals("falls at the same rate after the hold", -13f, b.hold, 1e-3f)
        assertEquals(-31f, b.peak, 1e-3f)
    }

    @Test fun neverBelowTheFloor() {
        val b = Ballistics()
        b.update(0, -50f, -60f)
        b.update(60_000 * ms, Ballistics.FLOOR_DB, Ballistics.FLOOR_DB)
        assertEquals(Ballistics.FLOOR_DB, b.peak)
        assertEquals(Ballistics.FLOOR_DB, b.rms)
        assertEquals(Ballistics.FLOOR_DB, b.hold)
    }

    @Test fun resetForgetsTime() {
        val b = Ballistics()
        b.update(0, -1f, -1f)
        b.reset()
        b.update(10_000 * ms, -20f, -30f)
        assertEquals(-20f, b.peak)
    }
}

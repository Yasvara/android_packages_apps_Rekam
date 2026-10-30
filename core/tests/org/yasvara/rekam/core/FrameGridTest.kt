/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.core

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGridTest {
    @Test fun jitterIsRemoved() {
        val g = FrameGrid(135f)
        val period = 1_000_000.0 / 135
        val rnd = Random(7)
        val out = (0 until 1000).map { i -> g.snap(1_000_000 + (i * period).toLong() + rnd.nextLong(-2_000, 2_001)) }
        val steps = out.zipWithNext { a, b -> b - a }
        assertTrue("every step is one period: ${steps.distinct()}", steps.all { abs(it - period) <= 1 })
    }

    @Test fun aSkippedCompositionKeepsItsGap() {
        val g = FrameGrid(60f)
        val p = 16_667L
        val out = listOf(0L, p + 900, 3 * p - 1_200, 4 * p).map { g.snap(10_000_000 + it) }.map { it - 10_000_000 }
        assertEquals(listOf(0L, 16_667L, 50_000L, 66_667L), out)
    }

    @Test fun twoFramesInOneSlotGoToConsecutiveSlots() {
        val g = FrameGrid(100f)
        val out = listOf(0L, 9_000, 11_000, 30_000).map(g::snap)
        assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L), out)
    }

    @Test fun staticScreenRepeatsStayWhereTheyAre() {
        val g = FrameGrid(60f)
        g.snap(0)
        assertEquals("a 100 ms repeat lands on the grid nearest 100 ms", 100_000L, g.snap(100_000))
    }

    @Test fun fractionalRatesDoNotDrift() {
        val g = FrameGrid(67.5f)
        var last = 0L
        for (i in 0..67_500) last = g.snap((i * 1_000_000.0 / 67.5).toLong())
        assertEquals("1000 s later still on time", 1_000_000_000L, last)
    }
}

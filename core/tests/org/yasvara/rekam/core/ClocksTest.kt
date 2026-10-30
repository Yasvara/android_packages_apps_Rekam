/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioClockTest {
    @Test fun nothingBeforeTheFirstAnchor() {
        val c = AudioClock(48_000)
        assertNull(c.ptsUs(0))
        c.anchor(0, 0) // a zero time is not a timestamp
        assertFalse(c.anchored)
    }

    @Test fun framesAdvanceTimeAtTheSampleRate() {
        val c = AudioClock(48_000)
        c.anchor(framePosition = 4_800, nanoTime = 1_000_000_000)
        assertEquals(900_000L, c.ptsUs(0)) // 100 ms before the anchor frame
        assertEquals(1_000_000L, c.ptsUs(4_800))
        assertEquals(1_021_333L, c.ptsUs(4_800 + 1_024))
    }

    @Test fun reanchoringNeverStepsBack() {
        val c = AudioClock(48_000)
        c.anchor(0, 1_000_000_000)
        val a = c.ptsUs(48_000)!!
        c.anchor(48_000, 1_999_000_000) // the capture clock runs 1 ms behind
        val b = c.ptsUs(48_000 + 1_024)!!
        assertTrue("$a then $b", b > a)
        assertEquals(2_999_000L, c.ptsUs(96_000))
        c.anchor(96_000, 2_990_000_000) // a 9 ms correction back
        assertEquals("held one µs past the last block", 2_999_001L, c.ptsUs(96_000))
    }
}

class PauseClockTest {
    @Test fun dropsWhatIsCapturedWhilePausedAndShiftsTheRest() {
        val p = PauseClock()
        assertEquals(1_000L, p.map(1_000))
        p.pause(2_000)
        assertTrue(p.paused)
        assertNull(p.map(2_500))
        assertNull("still paused: anything after the start is dropped", p.map(9_000))
        p.resume(5_000)
        assertFalse(p.paused)
        assertEquals("before the pause keeps its time", 1_999L, p.map(1_999))
        assertNull(p.map(4_999))
        assertEquals(2_000L, p.map(5_000))
        assertEquals(7_000L, p.map(10_000))
    }

    @Test fun pausesAddUp() {
        val p = PauseClock()
        p.pause(1_000); p.resume(2_000)
        p.pause(3_000); p.resume(6_000)
        assertEquals(4_000L, p.pausedUs(10_000))
        assertEquals(6_000L, p.map(10_000))
        assertEquals(1_500L, p.map(2_500))
        p.pause(8_000)
        assertEquals(6_000L, p.pausedUs(10_000))
    }

    @Test fun repeatedCallsAreHarmless() {
        val p = PauseClock()
        p.resume(100)
        p.pause(1_000); p.pause(1_500)
        p.resume(2_000); p.resume(3_000)
        assertEquals(1_000L, p.pausedUs(5_000))
        assertEquals(4_000L, p.map(5_000))
    }
}

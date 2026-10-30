/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePlanTest {
    private val self = 10_217

    @Test fun appsGetSlotsFromTwoAndAreExcludedFromOtherApps() {
        val p = SourcePlan(slots = 8, selfUid = self)
        assertEquals(listOf(10_100 to 2, 10_200 to 3), p.add(listOf(10_100, 10_200)))
        assertEquals(setOf(10_100, 10_200, self), p.excluded)
    }

    @Test fun rekamAndSystemUidsNeverGetAStrip() {
        val p = SourcePlan(8, self)
        assertTrue(p.add(listOf(self, 1000, 1041)).isEmpty())
        assertEquals(setOf(self), p.excluded)
    }

    @Test fun addingIsIdempotent() {
        val p = SourcePlan(8, self)
        p.add(listOf(10_100))
        assertTrue(p.add(listOf(10_100)).isEmpty())
        assertEquals(mapOf(10_100 to 2), p.appSlots)
    }

    @Test fun stopsWhenSlotsRunOut() {
        val p = SourcePlan(slots = 4, selfUid = self)
        assertEquals(listOf(10_001 to 2, 10_002 to 3), p.add(listOf(10_001, 10_002, 10_003)))
        assertEquals(setOf(10_001, 10_002, self), p.excluded, )
    }

    @Test fun removeReturnsTheAppToOtherAppsAndFreesItsSlot() {
        val p = SourcePlan(4, self)
        p.add(listOf(10_001, 10_002))
        p.remove(10_001)
        assertEquals(setOf(10_002, self), p.excluded)
        assertEquals(listOf(10_003 to 2), p.add(listOf(10_003)))
    }

    @Test fun clearFreesEverySlot() {
        val p = SourcePlan(4, self)
        p.add(listOf(10_001, 10_002))
        p.clear()
        assertEquals(listOf(10_009 to 2), p.add(listOf(10_009)))
    }
}

/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page layout is the one `rekam_engine::meters` writes (its tests pin the same words). */
class MeterReaderTest {
    private fun page(slots: Int) = ByteBuffer.allocateDirect(MeterReader.bytes(slots)).order(ByteOrder.nativeOrder())

    private fun ByteBuffer.publish(seq: Int, vararg levels: Float) {
        putInt(0, seq)
        putInt(4, levels.size / 2)
        levels.forEachIndexed { i, v -> putInt((2 + i) * 4, v.toRawBits()) }
    }

    @Test fun readsTheLastBlock() {
        val p = page(3)
        p.publish(2, -6f, -9f, -20f, -30f)
        val out = FloatArray(6)
        assertEquals(2, MeterReader(p).read(out))
        assertEquals(listOf(-6f, -9f, -20f, -30f), out.take(4))
    }

    @Test fun sequenceTellsNewBlocksFromOld() {
        val p = page(1)
        val r = MeterReader(p)
        p.publish(2, -1f, -1f)
        r.read(FloatArray(2))
        assertEquals(2, r.sequence)
        r.read(FloatArray(2))
        assertEquals("same block", 2, r.sequence)
        p.publish(4, -2f, -2f)
        r.read(FloatArray(2))
        assertEquals(4, r.sequence)
        p.publish(5, -3f, -3f)
        assertEquals(-1, r.read(FloatArray(2), 2))
        assertEquals("a failed read keeps the last", 4, r.sequence)
    }

    @Test fun anOddSequenceIsNeverRead() {
        val p = page(1)
        p.publish(3, -1f, -1f)
        assertEquals(-1, MeterReader(p).read(FloatArray(2)))
    }

    @Test fun boundedByThePageAndTheOutput() {
        val p = page(1)
        p.putInt(0, 2); p.putInt(4, 99)
        assertEquals(1, MeterReader(p).read(FloatArray(8)))
        val q = page(4)
        q.publish(2, 1f, 2f, 3f, 4f, 5f, 6f)
        assertEquals(1, MeterReader(q).read(FloatArray(2)))
    }

    @Test fun concurrentReadsAreNeverTorn() {
        val slots = 8
        val p = page(slots)
        val writer = thread {
            var seq = 0
            for (k in 0 until 200_000) {
                val v = -(k % 100).toFloat()
                p.putInt(0, ++seq) // odd: writing
                java.lang.invoke.VarHandle.storeStoreFence()
                p.putInt(4, slots)
                for (i in 0 until 2 * slots) p.putInt((2 + i) * 4, v.toRawBits())
                java.lang.invoke.VarHandle.releaseFence()
                p.putInt(0, ++seq)
            }
        }
        val r = MeterReader(p)
        val out = FloatArray(2 * slots)
        var reads = 0
        while (writer.isAlive) {
            val n = r.read(out, 64)
            if (n > 0) {
                assertTrue("torn: ${out.toList()}", out.take(2 * n).all { it == out[0] })
                reads++
            }
        }
        assertTrue(reads > 0)
    }
}

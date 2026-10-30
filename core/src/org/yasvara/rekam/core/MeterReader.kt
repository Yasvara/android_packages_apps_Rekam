/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.core

import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the engine's meter page (written by `rekam_engine::MeterPage`): a seqlock over 32-bit
 * native-endian words — sequence, slot count, then peak and RMS (dBFS, f32 bits) per slot. Never
 * blocks the writer; a read that races a write is retried. The fences pair with the writer's
 * release stores, as in StampedLock's optimistic reads.
 */
class MeterReader(page: ByteBuffer) {
    private val page = page.duplicate().order(ByteOrder.nativeOrder())
    private val slotsMax = maxOf(0, (page.capacity() / 4 - HEADER_WORDS) / 2)

    /** The block the last successful [read] returned; it changes once per mixed block. */
    var sequence = 0
        private set

    /** Copies `[peak, rms]` per slot into [out]; returns the slot count, or -1 if every try raced. */
    fun read(out: FloatArray, tries: Int = 8): Int {
        repeat(tries) {
            val s1 = page.getInt(0)
            VarHandle.acquireFence()
            if (s1 and 1 == 1) return@repeat
            val n = minOf(page.getInt(4), slotsMax, out.size / 2).coerceAtLeast(0)
            for (i in 0 until 2 * n) out[i] = Float.fromBits(page.getInt((HEADER_WORDS + i) * 4))
            VarHandle.acquireFence()
            if (page.getInt(0) == s1) {
                sequence = s1
                return n
            }
        }
        return -1
    }

    companion object {
        const val HEADER_WORDS = 2
        fun bytes(slots: Int) = (HEADER_WORDS + 2 * slots) * 4
    }
}

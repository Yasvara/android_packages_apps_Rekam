/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.engine

import java.nio.ByteBuffer

/** The Rust data plane (`librekam_jni`). Buffers must be direct. */
internal object Native {
    init {
        System.loadLibrary("rekam_jni")
    }

    const val AVC = 0
    const val HEVC = 1
    const val AAC = 2
    const val OPUS = 3
    const val FLAC = 4

    /** Per track 8 ints: codec, timescale, width, height, sampleRate, channels, avgBitrate, group. */
    @JvmStatic external fun recorderOpen(
        fd: Int, ints: IntArray, enabled: BooleanArray, csd: Array<ByteArray>,
        fragmentUs: Long, syncEvery: Int, budget: Int,
    ): Long

    /** 0 queued, 1 dropped, -1 writer failed. */
    @JvmStatic external fun recorderWrite(h: Long, track: Int, buf: ByteBuffer, offset: Int, size: Int, ptsUs: Long, key: Boolean): Int

    /** [bytes, durationUs, dropped, inFlight, failed]. */
    @JvmStatic external fun recorderStats(h: Long, out: LongArray)
    @JvmStatic external fun recorderFlush(h: Long)

    /** Null on success, else the error; frees the handle. */
    @JvmStatic external fun recorderFinish(h: Long): String?

    /** Frees the handle and leaves the file for recovery. */
    @JvmStatic external fun recorderAbandon(h: Long)

    /** 0 final, 1 repaired, 2 empty, -1 not ours or corrupt, -2 i/o. Takes the fd. */
    @JvmStatic external fun recover(fd: Int): Int

    /** [slots] slots, all inactive; slot 0 is the master clock. */
    @JvmStatic external fun mixerNew(slots: Int): Long
    @JvmStatic external fun mixerActive(h: Long, slot: Int, active: Boolean)
    /** Levels of every slot, then the master; [page] stays mapped until [mixerFree]. */
    @JvmStatic external fun mixerAttachMeters(h: Long, page: ByteBuffer): Boolean
    @JvmStatic external fun mixerPush(h: Long, slot: Int, buf: ByteBuffer, bytes: Int)
    @JvmStatic external fun mixerPull(h: Long, buf: ByteBuffer, offset: Int, bytes: Int, timeoutMs: Int): Boolean
    @JvmStatic external fun mixerGain(h: Long, slot: Int, db: Float)
    @JvmStatic external fun mixerMute(h: Long, slot: Int, muted: Boolean)
    @JvmStatic external fun mixerShape(h: Long, slot: Int, mono: Boolean, balance: Float)
    @JvmStatic external fun mixerMaster(h: Long, db: Float)
    @JvmStatic external fun mixerFree(h: Long)
}

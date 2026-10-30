/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.util.Log
import java.nio.ByteBuffer
import org.yasvara.rekam.core.FrameGrid
import org.yasvara.rekam.core.PauseClock

/**
 * Feeds encoder output into the native recorder. The file header needs every track's codec
 * config, so packets that arrive before the last format are held (copied) and replayed; after
 * that, codec buffers go straight to native with no copy on the Kotlin side.
 *
 * Video timestamps go on the session's [grid] first. Every timestamp then goes through [pause]:
 * what was captured while paused is dropped, the rest is shifted into one continuous timeline.
 * After a resume, video restarts on a key frame.
 */
internal class Muxer(
    private var pfd: ParcelFileDescriptor?,
    private val codecs: IntArray,
    private val budgetBytes: Int,
    private val pause: PauseClock,
    private val grid: FrameGrid,
) {
    private class Held(val track: Int, val data: ByteBuffer, val ptsUs: Long, val key: Boolean)

    private val formats = arrayOfNulls<MediaFormat>(codecs.size)
    private val held = ArrayList<Held>()
    private var heldBytes = 0
    private val lock = Any()
    private val stats = LongArray(5)

    @Volatile private var handle = 0L
    /** Video tracks waiting for a key frame after a resume. */
    @Volatile private var needKey = false
    @Volatile var failed = false
        private set

    fun setFormat(track: Int, format: MediaFormat) = synchronized(lock) {
        formats[track] = format
        if (handle == 0L && formats.all { it != null }) open()
    }

    /** Call when a pause ends: video must start again on a key frame. */
    fun resumed() {
        needKey = true
    }

    /** From any encoder thread; [buf] is the codec's output buffer, released by the caller after. */
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val video = isVideo(track)
        // One encoder thread per track: the grid only ever sees video, in order.
        val captured = if (video) grid.snap(info.presentationTimeUs) else info.presentationTimeUs
        val pts = pause.map(captured) ?: return
        if (video && needKey) {
            if (!key) return
            needKey = false
        }
        val h = handle
        if (h != 0L) {
            if (Native.recorderWrite(h, track, buf, info.offset, info.size, pts, key) < 0) failed = true
            return
        }
        synchronized(lock) {
            if (handle != 0L) {
                if (Native.recorderWrite(handle, track, buf, info.offset, info.size, pts, key) < 0) failed = true
                return
            }
            if (heldBytes + info.size > MAX_HELD) return
            val src = buf.duplicate()
            src.limit(info.offset + info.size)
            src.position(info.offset)
            val copy = ByteBuffer.allocateDirect(info.size)
            copy.put(src)
            copy.flip()
            held += Held(track, copy, pts, key)
            heldBytes += info.size
        }
    }

    private fun open() {
        val fd = pfd?.detachFd() ?: return
        pfd = null
        val ints = IntArray(codecs.size * 8)
        val csd = Array(codecs.size) { ByteArray(0) }
        val enabled = BooleanArray(codecs.size) { true }
        for (i in codecs.indices) {
            val f = formats[i]!!
            val video = isVideo(i)
            val o = i * 8
            ints[o] = codecs[i]
            ints[o + 1] = if (video) 90_000 else f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (video) {
                ints[o + 2] = f.getInteger(MediaFormat.KEY_WIDTH)
                ints[o + 3] = f.getInteger(MediaFormat.KEY_HEIGHT)
            } else {
                ints[o + 4] = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ints[o + 5] = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                ints[o + 6] = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) f.getInteger(MediaFormat.KEY_BIT_RATE) else 0
            }
            csd[i] = listOf("csd-0", "csd-1").mapNotNull { f.getByteBuffer(it) }.fold(ByteArray(0)) { acc, b ->
                acc + ByteArray(b.remaining()).also { b.duplicate().get(it) }
            }
        }
        handle = Native.recorderOpen(fd, ints, enabled, csd, FRAGMENT_US, SYNC_EVERY_FRAGMENT, budgetBytes)
        if (handle == 0L) {
            failed = true
            Log.e(TAG, "recorder failed to open")
            return
        }
        for (p in held) Native.recorderWrite(handle, p.track, p.data, 0, p.data.remaining(), p.ptsUs, p.key)
        held.clear()
        heldBytes = 0
    }

    private fun isVideo(track: Int) = codecs[track] == Native.AVC || codecs[track] == Native.HEVC

    /** [bytes, durationUs, dropped, inFlight, failed]; zeros before the file opens. */
    fun stats(): LongArray {
        val h = handle
        if (h != 0L) Native.recorderStats(h, stats)
        return stats
    }

    /** Finalizes; null on success. Encoders must have stopped. */
    fun finish(): String? = synchronized(lock) {
        val h = handle
        handle = 0L
        pfd?.close()
        pfd = null
        if (h == 0L) "nothing was recorded" else Native.recorderFinish(h)
    }

    /** Leaves the file unfinalized for recovery (engine error paths). */
    fun abandon() = synchronized(lock) {
        val h = handle
        handle = 0L
        pfd?.close()
        pfd = null
        if (h != 0L) Native.recorderAbandon(h)
    }

    companion object {
        private const val TAG = "Rekam"
        private const val FRAGMENT_US = 1_000_000L
        private const val SYNC_EVERY_FRAGMENT = 1
        private const val MAX_HELD = 16 shl 20
    }
}

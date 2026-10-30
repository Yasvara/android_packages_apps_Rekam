/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.probe

import android.content.ContentValues
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Random

/** S8: cost of one fragment write + fdatasync, on the app dir and on a MediaStore pending fd. */
@RunWith(AndroidJUnit4::class)
class S8FsyncTest {
    private fun log(line: String) = Probe.log("S8", line)

    // 250 ms and 1 s of 20 Mbps, 1 s of 40 Mbps.
    private val chunks = listOf(625_000, 2_500_000, 5_000_000)

    @Test
    fun appDir() {
        val f = File(Probe.ctx.getExternalFilesDir(null), "fsync.bin")
        try {
            FileOutputStream(f).channel.use { ch -> chunks.forEach { measure("appDir", ch, it, paced = false); measure("appDir", ch, it, paced = true) } }
        } finally { f.delete() }
    }

    @Test
    fun mediaStorePending() {
        val cr = Probe.ctx.contentResolver
        val uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "rekam-probe-fsync.mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/RekamProbe")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        try {
            cr.openFileDescriptor(uri, "rw")!!.use { pfd ->
                log("mediaStore fdTarget=${runCatching { android.system.Os.readlink("/proc/self/fd/${pfd.fd}") }.getOrNull()}")
                FileOutputStream(pfd.fileDescriptor).channel.use { ch -> chunks.forEach { measure("mediaStore", ch, it, paced = false); measure("mediaStore", ch, it, paced = true) } }
            }
        } finally { cr.delete(uri, null, null) }
    }

    /** Paced runs write once per 250 ms like a live recorder; unpaced runs are back to back. */
    private fun measure(where: String, ch: FileChannel, size: Int, paced: Boolean, n: Int = 40) {
        val data = ByteBuffer.allocateDirect(size).apply { val b = ByteArray(size); Random(3).nextBytes(b); put(b) }
        val write = LongArray(n)
        val sync = LongArray(n)
        val t0 = SystemClock.elapsedRealtimeNanos()
        for (i in 0 until n) {
            if (paced) {
                val wait = t0 + i * 250_000_000L - SystemClock.elapsedRealtimeNanos()
                if (wait > 0) Thread.sleep(wait / 1_000_000)
            }
            data.rewind()
            val a = SystemClock.elapsedRealtimeNanos()
            while (data.hasRemaining()) ch.write(data)
            val b = SystemClock.elapsedRealtimeNanos()
            ch.force(false)
            sync[i] = SystemClock.elapsedRealtimeNanos() - b
            write[i] = b - a
        }
        val mb = size * n / 1e6
        val wall = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
        log("$where chunk=${size / 1000}KB paced=$paced write p50/p99/max=${write.ms(.5)}/${write.ms(.99)}/${write.ms(1.0)}ms " +
            "fdatasync p50/p95/p99/max=${sync.ms(.5)}/${sync.ms(.95)}/${sync.ms(.99)}/${sync.ms(1.0)}ms MBps=%.0f".format(mb / wall))
        ch.truncate(0)
        ch.position(0)
    }
}

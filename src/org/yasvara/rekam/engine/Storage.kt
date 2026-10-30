/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recordings live in Movies/Rekam as MediaStore items, pending while being written. The journal
 * lists every file that is open or unfinished; recovery walks it.
 */
internal class Storage(private val context: Context) {
    private val journal = context.getSharedPreferences("journal", Context.MODE_PRIVATE)

    /** A new pending file named after [label]; returns its uri and a read/write fd. */
    fun create(label: String): Pair<Uri, ParcelFileDescriptor>? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val name = "Rekam_${label.replace(Regex("[^A-Za-z0-9._-]"), "_")}_$stamp.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Rekam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return null
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "rw") }.getOrNull()
        if (pfd == null) {
            context.contentResolver.delete(uri, null, null)
            return null
        }
        journal.edit().putStringSet(KEY, open() + uri.toString()).commit()
        return uri to pfd
    }

    fun open(): Set<String> = journal.getStringSet(KEY, emptySet()).orEmpty()

    /** Makes a finished file visible and drops it from the journal. */
    fun publish(uri: Uri) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        forget(uri)
    }

    fun discard(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
        forget(uri)
    }

    fun forget(uri: Uri) {
        journal.edit().putStringSet(KEY, open() - uri.toString()).commit()
    }

    /**
     * Recovers every journaled file except [live]: repaired ones are published, empty ones
     * deleted, unreadable ones kept for a later attempt. Returns the published uris.
     */
    fun recoverAll(live: Uri?): List<Uri> = synchronized(RECOVERY) {
        val published = ArrayList<Uri>()
        for (s in open()) {
            val uri = Uri.parse(s)
            if (uri == live) continue
            val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "rw") }.getOrNull()
            if (pfd == null) {
                forget(uri) // the item is gone (deleted by the user or expired)
                continue
            }
            when (val r = Native.recover(pfd.detachFd())) {
                0, 1 -> { publish(uri); published += uri }
                2 -> discard(uri)
                else -> Log.w(TAG, "recover $uri: $r")
            }
        }
        published
    }

    private companion object {
        const val TAG = "Rekam"
        const val KEY = "open"
        /** The boot job and the engine may both start recovery; one at a time per process. */
        val RECOVERY = Any()
    }
}

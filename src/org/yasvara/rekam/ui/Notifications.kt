/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.SystemClock
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import android.util.Size
import org.yasvara.rekam.R
import org.yasvara.rekam.core.EncoderPlan
import org.yasvara.rekam.client.RekamClient
import org.yasvara.rekam.engine.RekamService

internal class Notifications(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_RECORDING, context.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_SAVED, context.getString(R.string.channel_saved), NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    /**
     * Ongoing: a running timer (or the frozen time while [paused]), Pause/Resume and Stop. Tapping
     * it opens the controller. [sinceMs] is the timer's elapsedRealtime base, 0 while starting.
     */
    fun recording(sinceMs: Long, paused: Boolean, recordedMs: Long, target: String?): Notification {
        val running = sinceMs > 0 && !paused
        return Notification.Builder(context, CH_RECORDING)
            .setSmallIcon(R.drawable.ic_rekam)
            .setContentTitle(context.getString(
                when {
                    paused -> R.string.paused
                    target == null -> R.string.recording_screen
                    else -> R.string.recording_app
                }
            ))
            .apply { if (paused) setContentText(DateUtils.formatElapsedTime(recordedMs / 1000)) }
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(running)
            .setUsesChronometer(running)
            .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - sinceMs))
            .setContentIntent(controller())
            .addAction(action(if (paused) R.string.resume else R.string.pause, if (paused) RekamService.ACTION_RESUME else RekamService.ACTION_PAUSE, 2))
            .addAction(action(R.string.stop, RekamClient.ACTION_STOP, 0))
            .build()
    }

    fun update(n: Notification) = nm.notify(RECORDING, n)

    /** The saved recording with its first frame, length, size and format; Share and Delete. */
    fun saved(uri: Uri, recordedMs: Long, plan: EncoderPlan, afterError: Boolean) {
        val thumb = runCatching { context.contentResolver.loadThumbnail(uri, Size(THUMB_W, THUMB_H), null) }
            .onFailure { Log.w(TAG, "no thumbnail for $uri", it) }.getOrNull()
        val size = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        }.getOrDefault(0L)
        val details = listOf(
            DateUtils.formatElapsedTime(recordedMs / 1000),
            Formatter.formatShortFileSize(context, size),
            "${minOf(plan.width, plan.height)}p${plan.fpsLabel}",
        ).joinToString(" · ")
        val share = PendingIntent.getActivity(
            context, 3,
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                null,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val delete = PendingIntent.getService(
            context, 4, Intent(RekamService.ACTION_DELETE, uri).setClass(context, RekamService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(SAVED, Notification.Builder(context, CH_SAVED)
            .setSmallIcon(R.drawable.ic_rekam)
            .setContentTitle(context.getString(if (afterError) R.string.saved_after_error else R.string.saved))
            .setContentText(details)
            .apply {
                if (thumb != null) {
                    setLargeIcon(thumb)
                    setStyle(Notification.BigPictureStyle().bigPicture(thumb).bigLargeIcon(null as Icon?).setSummaryText(details))
                }
            }
            .setContentIntent(view(uri))
            .addAction(Notification.Action.Builder(null, context.getString(R.string.share), share).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.delete), delete).build())
            .setAutoCancel(true)
            .build())
    }

    fun cancelSaved() = nm.cancel(SAVED)

    /** After a crash or power loss: the files are safe; offer to record again. */
    fun recovered(uris: List<Uri>) {
        val thumb: Bitmap? = runCatching { context.contentResolver.loadThumbnail(uris.first(), Size(THUMB_W, THUMB_H), null) }.getOrNull()
        val again = PendingIntent.getService(
            context, 1, Intent(RekamService.ACTION_TOGGLE).setClass(context, RekamService::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(RECOVERED, Notification.Builder(context, CH_SAVED)
            .setSmallIcon(R.drawable.ic_rekam)
            .setContentTitle(context.resources.getQuantityString(R.plurals.recovered, uris.size, uris.size))
            .setContentText(context.getString(R.string.recovered_text))
            .apply { if (thumb != null) setLargeIcon(thumb).setStyle(Notification.BigPictureStyle().bigPicture(thumb)) }
            .setContentIntent(view(uris.first()))
            .addAction(Notification.Action.Builder(null, context.getString(R.string.record_again), again).build())
            .setAutoCancel(true)
            .build())
    }

    private fun action(label: Int, action: String, request: Int) = Notification.Action.Builder(
        null, context.getString(label),
        PendingIntent.getService(context, request, Intent(action).setClass(context, RekamService::class.java), PendingIntent.FLAG_IMMUTABLE),
    ).build()

    private fun controller() = PendingIntent.getActivity(
        context, 5, Intent(context, StartSheetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun view(uri: Uri) = PendingIntent.getActivity(
        context, uri.hashCode(),
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "Rekam"
        const val RECORDING = 1
        private const val SAVED = 2
        private const val RECOVERED = 3
        private const val CH_RECORDING = "recording"
        private const val CH_SAVED = "saved"
        private const val THUMB_W = 1280
        private const val THUMB_H = 720
    }
}

/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Foreground service of type mediaProjection; projection spikes run while it is up. */
class ProbeFgs : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("probe", "Probe", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "probe").setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Rekam probe").build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

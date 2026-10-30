/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.recovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.yasvara.rekam.engine.Storage
import org.yasvara.rekam.ui.Notifications

/**
 * After a reboot (crash, power loss, panic), repairs whatever was being recorded. Runs in the
 * engine's process, in the receiver itself: a background app may not start a service here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val storage = Storage(context)
        if (storage.open().isEmpty()) return
        val pending = goAsync()
        Thread({
            try {
                val recovered = storage.recoverAll(live = null)
                if (recovered.isNotEmpty()) Notifications(context).recovered(recovered)
            } finally {
                pending.finish()
            }
        }, "rk-recover").start()
    }
}

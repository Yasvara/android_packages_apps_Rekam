/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.ui

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.yasvara.rekam.RekamState
import org.yasvara.rekam.client.RekamClient
import org.yasvara.rekam.engine.RekamService

/** Tap: record the entire screen, or stop. Long-press opens [StartSheetActivity]. */
class RekamTileService : TileService() {
    private val scope = MainScope()
    private var client: RekamClient? = null
    private var job: Job? = null

    override fun onStartListening() {
        val c = RekamClient(this).also { it.connect() }
        client = c
        job = scope.launch { c.state.collect(::update) }
    }

    override fun onStopListening() {
        job?.cancel()
        client?.disconnect()
        client = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        startForegroundService(Intent(RekamService.ACTION_TOGGLE).setClass(this, RekamService::class.java))
    }

    private fun update(s: RekamState) {
        val tile = qsTile ?: return
        tile.state = if (s.phase == RekamState.IDLE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }
}

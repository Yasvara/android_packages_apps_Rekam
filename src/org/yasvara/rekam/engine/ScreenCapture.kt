/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.engine

import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.projection.IMediaProjection
import android.media.projection.IMediaProjectionManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Process
import android.os.ServiceManager
import android.view.Display
import android.view.Surface

/**
 * A consent-free projection (Rekam is privileged) of the entire screen or of one app's task
 * (a fresh launch cookie plus the task id), mirrored into [surface].
 */
internal class ScreenCapture(
    private val context: Context,
    private val handler: Handler,
    private val onStopped: () -> Unit,
) {
    lateinit var projection: MediaProjection
        private set
    private var display: VirtualDisplay? = null

    /** Creates the projection: the whole screen, or task [taskId] when it is not -1. */
    fun open(taskId: Int) {
        val mpm = IMediaProjectionManager.Stub.asInterface(ServiceManager.getService(Context.MEDIA_PROJECTION_SERVICE))
        val p = mpm.createProjection(Process.myUid(), context.packageName, MediaProjectionManager.TYPE_SCREEN_CAPTURE, false, Display.DEFAULT_DISPLAY)
        if (taskId >= 0) {
            p.setLaunchCookie(ActivityOptions.LaunchCookie())
            p.setTaskId(taskId)
        }
        projection = MediaProjection(context, IMediaProjection.Stub.asInterface(p.asBinder()))
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = onStopped()
        }, handler)
    }

    /**
     * Starts mirroring into [surface], composed at [fps]: SurfaceFlinger then hands the encoder
     * frames on an even vsync grid instead of every display refresh, so dropping down to the
     * session rate never makes an uneven cadence.
     */
    fun mirror(width: Int, height: Int, fps: Float, surface: Surface) {
        val dpi = context.resources.displayMetrics.densityDpi
        val config = VirtualDisplayConfig.Builder("Rekam", width, height, dpi)
            .setFlags(DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
            .setSurface(surface)
            .setRequestedRefreshRate(fps)
        display = projection.createVirtualDisplay(config, null, handler)
    }

    /** Detaches the encoder first so its end-of-stream is the last frame. */
    fun stop() {
        display?.release()
        display = null
        if (::projection.isInitialized) runCatching { projection.stop() }
    }

    companion object {
        /** Top task of [pkg], or -1. */
        fun taskOf(pkg: String): Int = runCatching {
            ActivityTaskManager.getService().getTasks(20, false, true, -1)
                .firstOrNull { it.topActivity?.packageName == pkg || it.baseActivity?.packageName == pkg }?.taskId
        }.getOrNull() ?: -1
    }
}

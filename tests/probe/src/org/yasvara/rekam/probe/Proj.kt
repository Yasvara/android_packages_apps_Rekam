/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.probe

import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.IMediaProjection
import android.media.projection.IMediaProjectionManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.ServiceManager
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Consent-free projections, the way a privileged recorder gets them (R1). */
object Proj {
    private val ctx get() = Probe.ctx
    val handler by lazy { Handler(HandlerThread("probe-proj").apply { start() }.looper) }

    /**
     * A platform-signed app that isn't preinstalled gets no signature permissions (A14+ allowlist),
     * so projection and capture spikes borrow the shell's identity, which holds all of them.
     */
    fun startFgs() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.adoptShellPermissionIdentity()
        ctx.startForegroundService(Intent(ctx, ProbeFgs::class.java))
        SystemClock.sleep(500)
    }

    fun stopFgs() {
        ctx.stopService(Intent(ctx, ProbeFgs::class.java))
        InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
    }

    /** Runs [cmd] as shell and returns its output. */
    fun shell(cmd: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText().trim()
        }

    fun create(taskId: Int = -1, overlay: Boolean = false): MediaProjection {
        val mpm = IMediaProjectionManager.Stub.asInterface(ServiceManager.getService(Context.MEDIA_PROJECTION_SERVICE))
        val p = mpm.createProjection(Process.myUid(), ctx.packageName, MediaProjectionManager.TYPE_SCREEN_CAPTURE, false, Display.DEFAULT_DISPLAY)
        if (taskId >= 0) {
            p.setLaunchCookie(ActivityOptions.LaunchCookie())
            p.setTaskId(taskId)
        }
        if (overlay) p.setRecordingOverlay(true)
        return MediaProjection(ctx, IMediaProjection.Stub.asInterface(p.asBinder())).also {
            it.registerCallback(object : MediaProjection.Callback() {}, handler)
        }
    }

    fun taskOf(pkg: String): Int? = ActivityTaskManager.getService().getTasks(20, false, true, -1)
        .firstOrNull { it.topActivity?.packageName == pkg || it.baseActivity?.packageName == pkg }?.taskId

    fun topTask(): Pair<Int, String>? = ActivityTaskManager.getService().getTasks(20, false, true, -1)
        .firstOrNull { it.isVisible && it.topActivity?.packageName != ctx.packageName }
        ?.let { it.taskId to (it.topActivity?.packageName ?: "?") }

    fun displaySize(): Pair<Int, Int> {
        val m = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).mode
        return m.physicalWidth to m.physicalHeight
    }

    fun virtualDisplay(p: MediaProjection, w: Int, h: Int, surface: Surface): VirtualDisplay =
        p.createVirtualDisplay("rekam-probe", w, h, 320, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler)!!

    /** First frame of [p]: ms from VD creation to it, plus [analyze] run on it (no image is kept). */
    fun firstFrame(p: MediaProjection, scale: Int = 2, analyze: ((Bitmap) -> String)? = null): Pair<Long, String?> {
        val (dw, dh) = displaySize()
        val w = dw / scale
        val h = dh / scale
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val got = CountDownLatch(1)
        var t1 = 0L
        var verdict: String? = null
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (got.count > 0) {
                t1 = SystemClock.elapsedRealtimeNanos()
                if (analyze != null) {
                    val pl = img.planes[0]
                    val bmp = Bitmap.createBitmap(pl.rowStride / pl.pixelStride, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(pl.buffer)
                    verdict = runCatching { analyze(Bitmap.createBitmap(bmp, 0, 0, w, h)) }.getOrElse { "analyze: $it" }
                }
                got.countDown()
            }
            img.close()
        }, handler)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val vd = virtualDisplay(p, w, h, reader.surface)
        val ok = got.await(3, TimeUnit.SECONDS)
        vd.release()
        reader.close()
        return (if (ok) (t1 - t0) / 1_000_000 else -1) to verdict
    }

    /** Share of pure-magenta pixels (the probe's overlay) in the centre square. */
    fun magentaPct(b: Bitmap): Double {
        val cx = b.width / 2; val cy = b.height / 2; val r = b.width / 6
        var hit = 0; var n = 0
        for (y in cy - r until cy + r step 2) for (x in cx - r until cx + r step 2) {
            val c = b.getPixel(x, y)
            if (android.graphics.Color.red(c) > 200 && android.graphics.Color.green(c) < 60 && android.graphics.Color.blue(c) > 200) hit++
            n++
        }
        return 100.0 * hit / n
    }

    /** Probe-owned TYPE_APPLICATION_OVERLAY square; [animate] repaints it every frame so a VD keeps producing. */
    fun overlay(size: Int = 400, animate: Boolean = false): () -> Unit {
        val wm = ctx.getSystemService(android.view.WindowManager::class.java)
        var view: android.view.View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val v = object : android.view.View(ctx) {
                var i = 0
                override fun onDraw(c: android.graphics.Canvas) {
                    c.drawColor(if (animate) android.graphics.Color.rgb(255, (i++ * 5) and 0x3f, 255) else android.graphics.Color.MAGENTA)
                    if (animate) postInvalidateOnAnimation()
                }
            }
            wm.addView(v, android.view.WindowManager.LayoutParams(size, size,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT).apply { gravity = android.view.Gravity.CENTER })
            view = v
        }
        SystemClock.sleep(400)
        return { InstrumentationRegistry.getInstrumentation().runOnMainSync { view?.let { wm.removeView(it) } } }
    }
}

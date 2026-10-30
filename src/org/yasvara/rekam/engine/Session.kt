/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import org.yasvara.rekam.RecordRequest
import org.yasvara.rekam.client.settings
import org.yasvara.rekam.core.EncoderPlan
import org.yasvara.rekam.core.FrameGrid
import org.yasvara.rekam.core.PauseClock

/**
 * One recording. Built and torn down on the `rk-ctl` thread:
 * projection → file → muxer → video encoder → audio encoder on the running [graph] → mirror.
 * Teardown runs in reverse so every encoder sees end-of-stream before the file is finalized.
 */
internal class Session(
    private val context: Context,
    private val request: RecordRequest,
    private val graph: AudioGraph,
    private val storage: Storage,
    private val ctl: Handler,
    private val onFinished: (Session, Uri?, String?, Long) -> Unit,
) {
    var uri: Uri? = null
        private set
    val target: String? get() = request.packageName
    lateinit var plan: EncoderPlan
        private set

    private val pause = PauseClock()
    private var startUs = 0L
    private var capture: ScreenCapture? = null
    private var video: VideoEncoder? = null
    private var audio: AudioEncoder? = null
    private var muxer: Muxer? = null
    private var error: String? = null
    private var stopped = false

    val paused: Boolean get() = pause.paused

    /** Recorded time, pauses excluded. */
    fun recordedMs(): Long = if (startUs == 0L) 0 else (nowUs() - startUs - pause.pausedUs(nowUs())) / 1000

    /** elapsedRealtime base of [recordedMs] for a chronometer. */
    fun sinceMs(): Long = SystemClock.elapsedRealtime() - recordedMs()

    /** False when nothing could start; [onFinished] then follows. */
    fun start(): Boolean = try {
        startInternal()
        true
    } catch (e: Exception) {
        Log.e(TAG, "start failed", e)
        fail(e.message ?: e.toString())
        false
    }

    private fun startInternal() {
        plan = Caps.plan(context, request.settings())
        val pkg = request.packageName
        val taskId = when {
            pkg == null -> -1
            request.taskId >= 0 -> request.taskId
            else -> ScreenCapture.taskOf(pkg).also { require(it >= 0) { "$pkg is not running" } }
        }
        val label = pkg?.let { p ->
            runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull()
        } ?: "Screen"
        val (u, pfd) = requireNotNull(storage.create(label)) { "storage unavailable" }
        uri = u
        val cap = ScreenCapture(context, ctl) { ctl.post { stop() } }
        capture = cap
        try {
            cap.open(taskId)
        } catch (e: Exception) {
            pfd.close()
            throw e
        }

        if (pkg != null) graph.addApp(pkg)
        graph.focus(pkg)
        val m = Muxer(pfd, Caps.muxerCodecs(plan), budgetBytes = (plan.videoBps / 8) * 4 + (1 shl 20), pause = pause, grid = FrameGrid(plan.fps))
        muxer = m
        val enc = VideoEncoder(plan, m, 0, ::fail).also { video = it }
        val aenc = AudioEncoder(plan, m, 1, ::fail).also { audio = it }
        enc.start()
        aenc.start()
        graph.attach { pcm, frames, pts -> if (pause.map(pts) != null) aenc.sink.onBlock(pcm, frames, pts) }
        cap.mirror(plan.width, plan.height, plan.fps, enc.surface)
        startUs = nowUs()
        Log.i(TAG, "recording ${pkg ?: "screen"} ${plan.width}x${plan.height}@${plan.fpsLabel} ${plan.videoBps / 1000} kbps " +
            "codec=${plan.videoCodec} rc=${plan.rateControl} audio=${plan.audioCodec}@${plan.audioBps / 1000} mic=${graph.micOpen}")
    }

    fun setPaused(p: Boolean) {
        if (stopped || startUs == 0L || p == pause.paused) return
        if (p) {
            pause.pause(nowUs())
            video?.setSuspended(true)
        } else {
            muxer?.resumed()
            pause.resume(nowUs())
            video?.setSuspended(false)
        }
    }

    /** [bytes, durationUs, dropped, inFlight, failed]. */
    fun stats(): LongArray = muxer?.stats() ?: LongArray(5)

    fun failed(): Boolean = muxer?.failed == true

    private fun fail(reason: String) {
        ctl.post {
            if (error == null) error = reason
            stop()
        }
    }

    /** Idempotent; must run on `rk-ctl`. */
    fun stop() {
        if (stopped) return
        stopped = true
        val recorded = recordedMs()
        capture?.stop()
        video?.stop()
        graph.attach(null)
        audio?.stop()
        graph.focus(null)
        val m = muxer
        val u = uri
        val result = when {
            m == null -> error ?: "nothing was recorded"
            error != null -> { m.abandon(); error }
            else -> m.finish()
        }
        val kept = when {
            u == null -> null
            result == null -> u.also(storage::publish)
            m == null -> null.also { storage.discard(u) }
            else -> recoverNow(u)
        }
        onFinished(this, kept, result, recorded)
    }

    /** A failed session's file is repaired right away, the same way recovery would after a crash. */
    private fun recoverNow(u: Uri): Uri? {
        Log.w(TAG, "recording ended with an error; recovering $u")
        return u.takeIf { it in storage.recoverAll(live = null) }
    }

    private companion object {
        const val TAG = "Rekam"
        /** CLOCK_MONOTONIC, the clock every capture timestamp uses. */
        fun nowUs() = System.nanoTime() / 1000
    }
}

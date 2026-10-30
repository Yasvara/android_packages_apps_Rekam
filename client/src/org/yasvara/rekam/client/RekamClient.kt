/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.os.SharedMemory
import android.util.Log
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.yasvara.rekam.AudioSource
import org.yasvara.rekam.IRekam
import org.yasvara.rekam.IRekamCallback
import org.yasvara.rekam.RecordRequest
import org.yasvara.rekam.RekamState
import org.yasvara.rekam.core.MeterReader
import org.yasvara.rekam.core.Settings

/**
 * The only way other apps talk to Rekam: binds the engine, mirrors its state, forwards commands.
 * Bind while visible ([connect]) and unbind when hidden ([disconnect]); the engine runs on its own.
 * Calls are cheap binder transactions and may come from any thread.
 */
class RekamClient(private val context: Context) {
    private val _state = MutableStateFlow(RekamState())
    val state: StateFlow<RekamState> = _state.asStateFlow()

    @Volatile private var service: IRekam? = null
    private var bound = false
    /** Identifies this client's audio watch; dies with the process. */
    private val watchToken = Binder()
    private var watching = false
    private var meterMemory: SharedMemory? = null
    private var meterPage: ByteBuffer? = null

    private val callback = object : IRekamCallback.Stub() {
        override fun onState(state: RekamState) {
            _state.value = state
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = IRekam.Stub.asInterface(binder)
            service = s
            call {
                s.registerCallback(callback)
                _state.value = s.state
                if (watching) s.watchAudio(watchToken, true)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            releaseMeters()
        }
    }

    /** Binds; [capabilities] passes the caller's while-in-use access (the mic meter needs it). */
    fun connect(capabilities: Boolean = false) {
        if (bound) return
        val flags = Context.BIND_AUTO_CREATE or if (capabilities) Context.BIND_INCLUDE_CAPABILITIES else 0
        bound = context.bindService(Intent(ACTION_BIND).setComponent(ENGINE), connection, flags)
    }

    fun disconnect() {
        if (!bound) return
        service?.let { s ->
            call {
                if (watching) s.watchAudio(watchToken, false)
                s.unregisterCallback(callback)
            }
        }
        watching = false
        releaseMeters()
        context.unbindService(connection)
        bound = false
        service = null
    }

    val recording: Boolean get() = _state.value.phase != RekamState.IDLE

    /** Starts recording with the last settings; [packageName] null records the entire screen. */
    fun start(packageName: String? = null) = start(RecordRequest().apply { this.packageName = packageName; useLast = true })

    fun start(request: RecordRequest) {
        val s = service
        if (s != null) call { s.start(request) } else context.startForegroundService(startIntent(request))
    }

    fun stop() {
        val s = service
        if (s != null) call { s.stop() } else context.startService(Intent(ACTION_STOP).setComponent(ENGINE))
    }

    fun setPaused(paused: Boolean) {
        service?.let { s -> call { s.setPaused(paused) } }
    }

    /** Keeps the engine's audio graph running for this client's meters and strips. */
    fun watchAudio(on: Boolean) {
        watching = on
        service?.let { s -> call { s.watchAudio(watchToken, on) } }
    }

    fun setSource(source: AudioSource) {
        service?.let { s -> call { s.setSource(source) } }
    }

    fun removeSource(id: String) {
        service?.let { s -> call { s.removeSource(id) } }
    }

    /** The engine's meter page, or null while not connected. */
    fun meters(): MeterReader? {
        meterPage?.let { return MeterReader(it) }
        val s = service ?: return null
        return runCatching {
            val mem = s.meters()
            val page = mem.mapReadOnly()
            meterMemory = mem
            meterPage = page
            MeterReader(page)
        }.onFailure { Log.w(TAG, "meters unavailable", it) }.getOrNull()
    }

    private fun releaseMeters() {
        meterPage?.let(SharedMemory::unmap)
        meterPage = null
        meterMemory?.close()
        meterMemory = null
    }

    private inline fun call(block: () -> Unit) {
        try {
            block()
        } catch (e: RemoteException) {
            Log.w(TAG, "Rekam call failed", e)
        }
    }

    companion object {
        private const val TAG = "RekamClient"
        val ENGINE = ComponentName("org.yasvara.rekam", "org.yasvara.rekam.engine.RekamService")
        const val ACTION_BIND = "org.yasvara.rekam.BIND"
        const val ACTION_START = "org.yasvara.rekam.START"
        const val ACTION_STOP = "org.yasvara.rekam.STOP"
        const val PERMISSION = "org.yasvara.rekam.permission.CONTROL"
        private const val EXTRA_REQUEST = "request"

        /** [ACTION_START] carrying [r]; the engine reads it back with [request]. */
        fun startIntent(r: RecordRequest): Intent = Intent(ACTION_START).setComponent(ENGINE).putExtra(EXTRA_REQUEST, r)

        fun request(intent: Intent): RecordRequest =
            intent.getParcelableExtra(EXTRA_REQUEST, RecordRequest::class.java) ?: RecordRequest().apply { useLast = true }
    }
}

/** The quality fields of a request, as the core models them. */
fun RecordRequest.settings() = Settings(
    preset = preset, videoCodec = videoCodec, rateControl = rateControl, videoKbps = videoKbps,
    shortSide = shortSide, fps = fps, keyframeMs = keyframeMs, audioCodec = audioCodec, audioKbps = audioKbps,
)

/** A request for [s]; target and mic as given. */
fun Settings.request(packageName: String? = null, taskId: Int = -1, mic: Boolean = false) = RecordRequest().also {
    it.packageName = packageName
    it.taskId = taskId
    it.mic = mic
    it.preset = preset
    it.videoCodec = videoCodec
    it.rateControl = rateControl
    it.videoKbps = videoKbps
    it.shortSide = shortSide
    it.fps = fps
    it.keyframeMs = keyframeMs
    it.audioCodec = audioCodec
    it.audioKbps = audioKbps
}

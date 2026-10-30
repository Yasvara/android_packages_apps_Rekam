/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.engine

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import android.os.SharedMemory
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import org.yasvara.rekam.AudioSource
import org.yasvara.rekam.IRekam
import org.yasvara.rekam.IRekamCallback
import org.yasvara.rekam.RecordRequest
import org.yasvara.rekam.RekamState
import org.yasvara.rekam.client.RekamClient
import org.yasvara.rekam.core.MeterReader
import org.yasvara.rekam.ui.Notifications

/**
 * The engine (process `:engine`). Exists while recording, or while a client is bound; foreground
 * while recording. All session and graph work runs on `rk-ctl`.
 *
 * The audio graph runs while a recording needs it or a UI watches ([IRekam.watchAudio]); its
 * meters live in one shared page for the life of the process.
 */
class RekamService : Service() {
    private lateinit var ctl: Handler
    private lateinit var storage: Storage
    private lateinit var notifications: Notifications
    private lateinit var settings: MixerSettings
    private val callbacks = RemoteCallbackList<IRekamCallback>()
    private val meters: SharedMemory = SharedMemory.create("rekam-meters", MeterReader.bytes(AudioGraph.SLOTS))
    private val page = meters.mapReadWrite().also { meters.setProtect(OsConstants.PROT_READ) }
    private var graph: AudioGraph? = null
    /** UIs that keep the graph running, by their client token. */
    private val watchers = HashMap<IBinder, IBinder.DeathRecipient>()
    private var session: Session? = null
    /** Set before a session is posted to `rk-ctl`, cleared when it ends: keeps the service alive. */
    @Volatile private var busy = false
    @Volatile private var state = RekamState()

    override fun onCreate() {
        super.onCreate()
        ctl = Handler(HandlerThread("rk-ctl").apply { start() }.looper)
        storage = Storage(this)
        notifications = Notifications(this)
        settings = MixerSettings(this)
        ctl.post {
            publish(RekamState())
            // A previous engine may have died mid-recording.
            recoverInterrupted()
        }
    }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == RekamClient.ACTION_BIND) binder else null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RekamClient.ACTION_START -> start(RekamClient.request(intent))
            RekamClient.ACTION_STOP -> stop()
            ACTION_TOGGLE -> if (!busy) start(RecordRequest().apply { useLast = true }) else stop()
            ACTION_PAUSE -> ctl.post { setPaused(true) }
            ACTION_RESUME -> ctl.post { setPaused(false) }
            ACTION_DELETE -> intent.data?.let { uri -> ctl.post { storage.discard(uri); notifications.cancelSaved() } }
        }
        ctl.post(::stopSelfIfIdle)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ctl.post {
            session?.stop()
            graph?.stop()
            graph = null
        }
        ctl.looper.quitSafely()
        super.onDestroy()
    }

    private fun start(r: RecordRequest) {
        if (busy) return
        busy = true
        val request = settings.complete(r)
        // Foreground before any capture: a binder-started session promotes itself too.
        startForegroundService(Intent(this, RekamService::class.java))
        // Mic/Aux can be unmuted at any point of a recording: the microphone type is always held
        // when the permission is.
        val micAllowed = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            if (micAllowed) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        startForeground(Notifications.RECORDING, notifications.recording(0L, false, 0L, request.packageName), types)
        ctl.post {
            if (session != null) return@post
            settings.setLast(request)
            publish(RekamState().apply { phase = RekamState.STARTING; target = request.packageName })
            val g = graph()
            g.setMicOpen(request.mic && micAllowed)
            val s = Session(this, request, g, storage, ctl, ::finished)
            session = s
            Engine.liveUri = null
            if (s.start()) {
                Engine.liveUri = s.uri
                publish(recordingState(s))
                startForeground(Notifications.RECORDING, notifications.recording(s.sinceMs(), false, 0L, request.packageName), types)
                ctl.postAtTime(::tick, TICK_TOKEN, SystemClock.uptimeMillis() + TICK_MS)
            }
        }
    }

    private fun stop() = ctl.post {
        val s = session ?: return@post
        publish(recordingState(s).apply { phase = RekamState.STOPPING })
        s.stop()
    }

    private fun setPaused(paused: Boolean) {
        val s = session ?: return
        s.setPaused(paused)
        publish(recordingState(s))
        notifications.update(notifications.recording(s.sinceMs(), s.paused, s.recordedMs(), s.target))
    }

    private fun finished(s: Session, uri: Uri?, error: String?, recordedMs: Long) {
        if (session !== s) return
        session = null
        busy = false
        Engine.liveUri = null
        ctl.removeCallbacksAndMessages(TICK_TOKEN)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (error != null) Log.w(TAG, "session ended: $error")
        if (uri != null) notifications.saved(uri, recordedMs, s.plan, error != null)
        publish(RekamState().apply { lastUri = uri?.toString(); this.error = error })
        releaseGraphIfIdle()
        stopSelfIfIdle()
    }

    private fun tick() {
        val s = session ?: return
        if (s.failed()) {
            s.stop()
            return
        }
        publish(recordingState(s))
        ctl.postAtTime(::tick, TICK_TOKEN, SystemClock.uptimeMillis() + TICK_MS)
    }

    private fun recordingState(s: Session): RekamState {
        val st = s.stats()
        return RekamState().apply {
            phase = RekamState.RECORDING
            sinceMs = s.sinceMs()
            paused = s.paused
            recordedMs = s.recordedMs()
            bytes = st[0]
            durationUs = st[1]
            dropped = st[2]
            target = s.target
        }
    }

    /** The running graph, started if needed. */
    private fun graph(): AudioGraph = graph ?: AudioGraph(this, settings, page, ctl, onSources = { publish(state) }, onError = ::graphError)
        .also {
            graph = it
            it.start()
        }

    private fun graphError(reason: String) = ctl.post {
        Log.w(TAG, "audio: $reason")
        // A lost source during a recording ends it the same way an encoder error does.
        session?.stop()
    }

    private fun releaseGraphIfIdle() {
        if (session != null || watchers.isNotEmpty()) return
        graph?.stop()
        graph = null
        publish(state)
    }

    private fun watch(token: IBinder, on: Boolean) {
        if (on) {
            if (token in watchers) return
            val death = IBinder.DeathRecipient { ctl.post { watch(token, false) } }
            if (runCatching { token.linkToDeath(death, 0) }.isFailure) return
            watchers[token] = death
            graph()
            publish(state)
        } else {
            val death = watchers.remove(token) ?: return
            runCatching { token.unlinkToDeath(death, 0) }
            releaseGraphIfIdle()
            stopSelfIfIdle()
        }
    }

    private fun recoverInterrupted() {
        val recovered = storage.recoverAll(live = Engine.liveUri)
        if (recovered.isNotEmpty()) notifications.recovered(recovered)
        stopSelfIfIdle()
    }

    private fun publish(s: RekamState) {
        s.sources = graph?.sources?.toTypedArray() ?: emptyArray()
        s.last = settings.last()
        state = s
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) runCatching { callbacks.getBroadcastItem(i).onState(s) }
        callbacks.finishBroadcast()
    }

    private fun stopSelfIfIdle() {
        if (!busy && callbacks.registeredCallbackCount == 0 && watchers.isEmpty()) stopSelf()
    }

    private val binder = object : IRekam.Stub() {
        private fun enforce() {
            if (Binder.getCallingUid() == Process.myUid()) return
            if (checkCallingPermission(RekamClient.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("requires ${RekamClient.PERMISSION}")
            }
        }

        override fun getState(): RekamState { enforce(); return this@RekamService.state }
        override fun start(request: RecordRequest) { enforce(); this@RekamService.start(request) }
        override fun stop() { enforce(); this@RekamService.stop() }
        override fun registerCallback(cb: IRekamCallback) { enforce(); callbacks.register(cb) }
        override fun unregisterCallback(cb: IRekamCallback) { enforce(); callbacks.unregister(cb) }

        override fun meters(): SharedMemory {
            enforce()
            return meters
        }

        override fun watchAudio(token: IBinder, watching: Boolean) {
            enforce()
            ctl.post { watch(token, watching) }
        }

        override fun setSource(source: AudioSource) {
            enforce()
            ctl.post { graph?.setSource(source) ?: settings.save(source) }
        }

        override fun removeSource(id: String) {
            enforce()
            ctl.post { graph?.removeSource(id) }
        }

        override fun setPaused(paused: Boolean) {
            enforce()
            ctl.post { this@RekamService.setPaused(paused) }
        }
    }

    companion object {
        private const val TAG = "Rekam"
        const val ACTION_TOGGLE = "org.yasvara.rekam.TOGGLE"
        const val ACTION_PAUSE = "org.yasvara.rekam.PAUSE"
        const val ACTION_RESUME = "org.yasvara.rekam.RESUME"
        const val ACTION_DELETE = "org.yasvara.rekam.DELETE"
        private const val TICK_MS = 1000L
        private val TICK_TOKEN = Any()
    }
}

/** Process-wide engine facts shared with the recovery job. */
internal object Engine {
    /** The file being written right now; recovery must leave it alone. */
    @Volatile var liveUri: Uri? = null
}

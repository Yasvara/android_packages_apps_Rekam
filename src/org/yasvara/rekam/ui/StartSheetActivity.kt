/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.ui

import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.yasvara.rekam.AudioSource
import org.yasvara.rekam.R
import org.yasvara.rekam.RekamState
import org.yasvara.rekam.client.RekamClient
import org.yasvara.rekam.client.request
import org.yasvara.rekam.client.settings
import org.yasvara.rekam.core.EncoderPlan
import org.yasvara.rekam.core.Settings
import org.yasvara.rekam.engine.AudioGraph
import org.yasvara.rekam.engine.Caps

/**
 * Rekam's controller (QS long-press, the status-bar chip, the notification). Idle: what to record
 * (entire screen, or an app from its live thumbnail), quality, and the audio mixer. Recording:
 * pause, stop and the live mixer. The mixer's meters run whenever the sheet is visible.
 */
class StartSheetActivity : ComponentActivity() {
    private lateinit var client: RekamClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The microphone meter captures while this sheet is in front: pass that right on.
        client = RekamClient(this).also { it.connect(capabilities = true) }
        val tasks = openTasks(this)
        setContent {
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Sheet(tasks)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        client.watchAudio(true)
    }

    override fun onStop() {
        client.watchAudio(false)
        super.onStop()
    }

    override fun onDestroy() {
        client.disconnect()
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Sheet(tasks: List<AppTask>) {
        val state by client.state.collectAsState()
        val recording = state.phase != RekamState.IDLE
        var settings by remember { mutableStateOf<Settings?>(null) }
        // The engine remembers the last settings; they arrive with its first state.
        LaunchedEffect(state.last) {
            val last = state.last ?: return@LaunchedEffect
            if (settings == null) {
                settings = last.settings()
            }
        }
        var single by remember { mutableStateOf(false) }
        var task by remember { mutableIntStateOf(tasks.firstOrNull()?.taskId ?: -1) }
        val needles = rememberNeedles(client, live = state.sources.isNotEmpty(), slots = AudioGraph.SLOTS + 1)
        val ctx = LocalContext.current
        val current = settings ?: Settings()
        val plan = remember(current) { runCatching { Caps.plan(ctx, current) }.getOrNull() }

        ModalBottomSheet(onDismissRequest = ::finish, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Header(state)
                Storage(state, plan)
                if (recording) {
                    Controls(state)
                } else {
                    Label(stringResource(R.string.record_what))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(R.string.entire_screen, R.string.single_app).forEachIndexed { i, label ->
                            SegmentedButton(single == (i == 1), { single = i == 1 }, SegmentedButtonDefaults.itemShape(i, 2)) {
                                Text(stringResource(label))
                            }
                        }
                    }
                    AnimatedVisibility(single) {
                        if (tasks.isEmpty()) {
                            Text(stringResource(R.string.no_apps), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            TaskCarousel(tasks, task) { task = it.taskId }
                        }
                    }
                    Quality(current, plan) { settings = it }
                }

                Label(stringResource(R.string.audio_mixer))
                Mixer(state, needles)

                if (!recording) {
                    val picked = tasks.firstOrNull { it.taskId == task }.takeIf { single }
                    Button(
                        onClick = {
                            val mic = state.sources.any { it.kind == AudioSource.KIND_MIC && !it.muted }
                            client.start((settings ?: Settings()).request(picked?.pkg, picked?.taskId ?: -1, mic))
                            finish()
                        },
                        enabled = !single || picked != null,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text(stringResource(R.string.start)) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    /** Brand; while recording a dot (amber when paused), the recorded time and the file size. */
    @Composable
    private fun Header(state: RekamState) {
        val cs = MaterialTheme.colorScheme
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_rekam), null, Modifier.size(24.dp), tint = cs.primary)
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (state.phase == RekamState.RECORDING) {
                var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        now = SystemClock.elapsedRealtime()
                        delay(250)
                    }
                }
                val recorded = if (state.paused) state.recordedMs else now - state.sinceMs
                val lit = state.paused || (now / 500) % 2 == 0L
                val dot = if (state.paused) AMBER else cs.error
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (lit) dot else dot.copy(alpha = 0.35f)))
                Spacer(Modifier.size(8.dp))
                Text(
                    "${DateUtils.formatElapsedTime(recorded / 1000)} · ${Formatter.formatShortFileSize(LocalContext.current, state.bytes)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }

    /**
     * Free space as a bar, and how long it lasts: at the rate being written while recording, at
     * the chosen quality before.
     */
    @Composable
    private fun Storage(state: RekamState, plan: EncoderPlan?) {
        val cs = MaterialTheme.colorScheme
        val ctx = LocalContext.current
        var stat by remember { mutableStateOf(freeSpace()) }
        LaunchedEffect(state.phase) {
            while (true) {
                stat = freeSpace()
                delay(2000)
            }
        }
        val (free, total) = stat
        val bytesPerSecond = when {
            state.phase == RekamState.RECORDING && state.recordedMs > 2000 -> state.bytes * 1000 / state.recordedMs
            plan != null -> (plan.videoBps + plan.audioBps) / 8L
            else -> 0L
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(
                progress = { if (total > 0) 1f - free.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (free < LOW_SPACE) cs.error else cs.primary,
                trackColor = cs.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            val left = if (bytesPerSecond > 0) DateUtils.formatElapsedTime(free / bytesPerSecond) else null
            Text(
                listOfNotNull(stringResource(R.string.storage_free, Formatter.formatShortFileSize(ctx, free)),
                    left?.let { stringResource(R.string.storage_left, it) }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = if (free < LOW_SPACE) cs.error else cs.onSurfaceVariant,
            )
        }
    }

    /** (free, total) bytes where recordings go. */
    private fun freeSpace(): Pair<Long, Long> = runCatching {
        val s = StatFs(Environment.getExternalStorageDirectory().path)
        s.availableBytes to s.totalBytes
    }.getOrDefault(0L to 0L)

    @Composable
    private fun Controls(state: RekamState) {
        val live = state.phase == RekamState.RECORDING
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { client.setPaused(!state.paused) }, enabled = live, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(painterResource(if (state.paused) R.drawable.ic_play else R.drawable.ic_pause), null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(if (state.paused) R.string.resume else R.string.pause))
            }
            Button(
                onClick = { client.stop(); finish() },
                enabled = live,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Icon(painterResource(R.drawable.ic_stop), null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.stop))
            }
        }
    }

    /** Preset, frame rate and what they resolve to on this device; the rest behind Advanced. */
    @Composable
    private fun Quality(s: Settings, plan: EncoderPlan?, onChange: (Settings) -> Unit) {
        var advanced by remember { mutableStateOf(false) }
        Label(stringResource(R.string.quality))
        Chips(PRESETS.map { (p, l) -> p to stringResource(l) }.filter { it.first != Settings.PRESET_CUSTOM || s.preset == Settings.PRESET_CUSTOM }, s.preset) {
            onChange(s.withPreset(it))
        }
        Label(stringResource(R.string.frame_rate))
        Chips(
            Settings.FPS_CHOICES.map { it to if (it == Settings.FPS_DISPLAY) stringResource(R.string.fps_display) else stringResource(R.string.fps, it) },
            s.fps,
        ) { onChange(s.copy(fps = it)) }
        plan?.let { Text(summary(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.advanced), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(advanced, { advanced = it })
        }
        AnimatedVisibility(advanced) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                fun custom(c: Settings) = onChange(c.copy(preset = Settings.PRESET_CUSTOM))
                Label(stringResource(R.string.video_codec))
                Segments(listOf(Settings.VIDEO_HEVC to "HEVC", Settings.VIDEO_AVC to "AVC"), s.videoCodec) { custom(s.copy(videoCodec = it)) }
                Label(stringResource(R.string.rate_control))
                Segments(
                    listOf(Settings.RC_VBR to stringResource(R.string.rc_vbr), Settings.RC_CBR to stringResource(R.string.rc_cbr),
                        Settings.RC_QUALITY to stringResource(R.string.rc_quality)),
                    s.rateControl,
                ) { custom(s.copy(rateControl = it)) }
                Label(stringResource(R.string.resolution))
                Chips(Settings.SHORT_SIDES.map { it to if (it == 0) stringResource(R.string.native_resolution) else "${it}p" }, s.shortSide) {
                    custom(s.copy(shortSide = it))
                }
                Label(stringResource(R.string.video_bitrate) + " · " +
                    if (s.videoKbps == 0) stringResource(R.string.auto) else stringResource(R.string.mbps, s.videoKbps / 1000))
                Slider(
                    value = s.videoKbps / 1000f,
                    onValueChange = { custom(s.copy(videoKbps = it.roundToInt() * 1000)) },
                    valueRange = 0f..(Settings.MAX_VIDEO_KBPS / 1000f),
                )
                Label(stringResource(R.string.keyframe_interval))
                Chips(Settings.KEYFRAME_MS.map { it to stringResource(R.string.seconds, (it / 1000f).toString().removeSuffix(".0")) }, s.keyframeMs) {
                    custom(s.copy(keyframeMs = it))
                }
                Label(stringResource(R.string.audio_codec))
                Segments(listOf(Settings.AUDIO_AAC to "AAC", Settings.AUDIO_OPUS to "Opus", Settings.AUDIO_FLAC to "FLAC"), s.audioCodec) {
                    custom(s.copy(audioCodec = it))
                }
                if (s.audioCodec != Settings.AUDIO_FLAC) {
                    Label(stringResource(R.string.audio_bitrate))
                    Chips(AUDIO_KBPS.map { it to stringResource(R.string.kbps, it) }, s.audioKbps) { custom(s.copy(audioKbps = it)) }
                }
            }
        }
    }

    @Composable
    private fun summary(p: EncoderPlan): String {
        val video = if (p.videoCodec == Settings.VIDEO_AVC) "AVC" else "HEVC"
        val rc = stringResource(when (p.rateControl) { Settings.RC_CBR -> R.string.rc_cbr; Settings.RC_QUALITY -> R.string.rc_quality; else -> R.string.rc_vbr })
        val audio = when (p.audioCodec) {
            Settings.AUDIO_OPUS -> "Opus ${p.audioBps / 1000} kbps"
            Settings.AUDIO_FLAC -> "FLAC " + stringResource(R.string.lossless)
            else -> "AAC ${p.audioBps / 1000} kbps"
        }
        return "$video · ${p.width}×${p.height} · ${p.fpsLabel} fps · ${stringResource(R.string.mbps, (p.videoBps / 1_000_000.0).roundToInt())} $rc · $audio"
    }

    /**
     * One strip per source: Desktop Audio, each app that plays, Mic/Aux, and the master after the
     * limiter. Changes apply live and are remembered.
     */
    @Composable
    private fun Mixer(state: RekamState, needles: List<Needle>) {
        val ctx = LocalContext.current
        val volume = painterResource(R.drawable.ic_volume)
        val volumeOff = painterResource(R.drawable.ic_volume_off)
        val micOn = painterResource(R.drawable.ic_mic)
        val micOff = painterResource(R.drawable.ic_mic_off)
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (state.sources.isEmpty()) {
                    Text(stringResource(R.string.starting_audio), Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.sources.forEachIndexed { i, src ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    val app = remember(src.packageName) {
                        src.packageName?.let { p ->
                            runCatching {
                                val ai = ctx.packageManager.getApplicationInfo(p, 0)
                                ctx.packageManager.getApplicationLabel(ai).toString() to
                                    ctx.packageManager.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap()
                            }.getOrNull()
                        }
                    }
                    val isMic = src.kind == AudioSource.KIND_MIC
                    ChannelStrip(
                        source = src,
                        name = when (src.kind) {
                            AudioSource.KIND_DESKTOP -> stringResource(R.string.desktop_audio)
                            AudioSource.KIND_MIC -> stringResource(R.string.mic_aux)
                            AudioSource.KIND_MASTER -> stringResource(R.string.master)
                            else -> app?.first ?: src.packageName ?: src.id
                        },
                        icon = app?.let { StripIcon.App(it.second) } ?: if (isMic) StripIcon.Symbol(micOn, micOff) else StripIcon.Symbol(volume, volumeOff),
                        mutedIcon = if (isMic) micOff else volumeOff,
                        unmutedIcon = if (isMic) micOn else volume,
                        needle = needles.getOrNull(src.slot),
                        onChange = client::setSource,
                        onMerge = if (src.kind == AudioSource.KIND_APP && src.packageName != state.target) {
                            { client.removeSource(src.id) }
                        } else null,
                        note = if (src.kind == AudioSource.KIND_MASTER) stringResource(R.string.limiter_note) else null,
                    )
                }
                if (state.sources.isNotEmpty()) MeterScale()
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun <T> Chips(items: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((v, label) in items) FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label) })
        }
    }

    @Composable
    private fun <T> Segments(items: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, (v, label) ->
                SegmentedButton(v == selected, { onPick(v) }, SegmentedButtonDefaults.itemShape(i, items.size)) { Text(label, maxLines = 1) }
            }
        }
    }

    @Composable
    private fun Label(text: String) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
    }

    private companion object {
        val PRESETS = listOf(
            Settings.PRESET_BALANCED to R.string.preset_balanced,
            Settings.PRESET_QUALITY to R.string.preset_quality,
            Settings.PRESET_SMALL to R.string.preset_small,
            Settings.PRESET_EDITING to R.string.preset_editing,
            Settings.PRESET_CUSTOM to R.string.preset_custom,
        )
        val AUDIO_KBPS = listOf(128, 192, 256, 320, 512)
        val AMBER = Color(0xFFF2B33D)
        /** Below this the bar turns red. */
        const val LOW_SPACE = 2L shl 30
    }
}

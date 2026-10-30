/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.yasvara.rekam.AudioSource
import org.yasvara.rekam.R
import org.yasvara.rekam.client.RekamClient
import org.yasvara.rekam.core.Ballistics

/** Meter range, as on a broadcast mixer: −60 dBFS to full scale. */
private const val METER_MIN_DB = -60f
private const val MAX_BOOST_DB = 30f

private val ZONES = listOf(
    METER_MIN_DB to -20f to Color(0xFF4CC46A),
    -20f to -9f to Color(0xFFF2C744),
    -9f to 0f to Color(0xFFEF5350),
)
private val SCALE = listOf(-60, -45, -30, -20, -10, -5, 0)

/** One slot's needle on screen, driven by [Ballistics]. */
@Stable
internal class Needle {
    private val b = Ballistics()
    var peak by mutableFloatStateOf(Ballistics.FLOOR_DB)
        private set
    var rms by mutableFloatStateOf(Ballistics.FLOOR_DB)
        private set
    var hold by mutableFloatStateOf(Ballistics.FLOOR_DB)
        private set

    fun update(nowNs: Long, peakIn: Float, rmsIn: Float) {
        b.update(nowNs, peakIn, rmsIn)
        peak = b.peak
        rms = b.rms
        hold = b.hold
    }
}

/**
 * Needles for every slot, animated each frame from the engine's meter page while [live]: a new
 * mixed block feeds them, frames in between let them fall. Shared memory only, no binder.
 */
@Composable
internal fun rememberNeedles(client: RekamClient, live: Boolean, slots: Int): List<Needle> {
    val needles = remember(slots) { List(slots) { Needle() } }
    LaunchedEffect(live, slots) {
        if (!live) return@LaunchedEffect
        val levels = FloatArray(2 * slots)
        var seen = -1
        while (true) {
            val now = withFrameNanos { it }
            val reader = client.meters() ?: continue
            val n = reader.read(levels)
            val fresh = n > 0 && reader.sequence != seen
            if (fresh) seen = reader.sequence
            needles.forEachIndexed { i, needle ->
                if (fresh && i < n) needle.update(now, levels[2 * i], levels[2 * i + 1])
                else needle.update(now, Ballistics.FLOOR_DB, Ballistics.FLOOR_DB)
            }
        }
    }
    return needles
}

/** What a strip shows as its icon: an app's icon or a symbol. */
internal sealed interface StripIcon {
    class App(val bitmap: ImageBitmap) : StripIcon
    class Symbol(val on: Painter, val off: Painter) : StripIcon
}

/** A copy of [s] with [change] applied: strips go to the engine whole. */
internal fun AudioSource.with(change: AudioSource.() -> Unit): AudioSource = AudioSource().also {
    it.id = id
    it.kind = kind
    it.packageName = packageName
    it.slot = slot
    it.volumeDb = volumeDb
    it.boostDb = boostDb
    it.muted = muted
    it.mono = mono
    it.balance = balance
    it.change()
}

/**
 * A mixer channel: icon, name, the fader's value, mute and the filters toggle; the meter, and
 * under it the fader on the same dB axis (−∞ at the left, 0 dB at the right), so moving the
 * fader moves the meter by the same amount. Filters: gain, balance, mono, and for an app strip,
 * merging it back into Desktop Audio.
 */
@Composable
internal fun ChannelStrip(
    source: AudioSource,
    name: String,
    icon: StripIcon,
    mutedIcon: Painter,
    unmutedIcon: Painter,
    needle: Needle?,
    onChange: (AudioSource) -> Unit,
    onMerge: (() -> Unit)? = null,
    note: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    // Local while dragging so the fader never lags behind the engine's round trip.
    var volume by remember(source.volumeDb) { mutableFloatStateOf(source.volumeDb) }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (icon) {
                is StripIcon.App -> Image(icon.bitmap, null, Modifier.size(22.dp))
                is StripIcon.Symbol -> Icon(if (source.muted) icon.off else icon.on, null, Modifier.size(22.dp), tint = cs.onSurfaceVariant)
            }
            Spacer(Modifier.size(10.dp))
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.db, formatDb(volume)),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            FilledTonalIconToggleButton(
                checked = source.muted,
                onCheckedChange = { m -> onChange(source.with { muted = m }) },
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    checkedContainerColor = cs.errorContainer,
                    checkedContentColor = cs.onErrorContainer,
                ),
            ) {
                Icon(if (source.muted) mutedIcon else unmutedIcon, stringResource(R.string.mute), Modifier.size(20.dp))
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                Icon(painterResource(R.drawable.ic_more), stringResource(R.string.more), Modifier.size(20.dp), tint = cs.onSurfaceVariant)
            }
        }
        Meter(needle, dimmed = source.muted)
        Slider(
            value = volume,
            onValueChange = { v ->
                // Half-dB steps keep the binder quiet while dragging; the left end is silence.
                val snapped = (v * 2).roundToInt() / 2f
                if (snapped != volume) {
                    volume = snapped
                    onChange(source.with { volumeDb = snapped })
                }
            },
            valueRange = METER_MIN_DB..0f,
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        AnimatedVisibility(expanded) {
            Filters(source, onChange, onMerge, note)
        }
    }
}

@Composable
private fun Filters(source: AudioSource, onChange: (AudioSource) -> Unit, onMerge: (() -> Unit)?, note: String?) {
    val cs = MaterialTheme.colorScheme
    var boost by remember(source.boostDb) { mutableFloatStateOf(source.boostDb) }
    var balance by remember(source.balance) { mutableFloatStateOf(source.balance) }
    val label = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
    Column(Modifier.fillMaxWidth().padding(start = 32.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gain, formatGain(boost)), style = label, color = cs.onSurfaceVariant, modifier = Modifier.width(112.dp))
            Slider(
                value = boost,
                onValueChange = { v ->
                    val snapped = (v * 2).roundToInt() / 2f
                    if (snapped != boost) {
                        boost = snapped
                        onChange(source.with { boostDb = snapped })
                    }
                },
                valueRange = 0f..MAX_BOOST_DB,
                modifier = Modifier.weight(1f).height(28.dp),
            )
        }
        if (source.kind != AudioSource.KIND_MASTER) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (l, r) = ((1f - balance).coerceAtMost(1f) * 100).roundToInt() to ((1f + balance).coerceAtMost(1f) * 100).roundToInt()
                Text(stringResource(R.string.balance_value, l, r), style = label, color = cs.onSurfaceVariant, modifier = Modifier.width(112.dp))
                Slider(
                    value = balance,
                    onValueChange = { v ->
                        // Detent at the centre.
                        val snapped = if (abs(v) < 0.05f) 0f else (v * 20).roundToInt() / 20f
                        if (snapped != balance) {
                            balance = snapped
                            onChange(source.with { this.balance = snapped })
                        }
                    },
                    valueRange = -1f..1f,
                    modifier = Modifier.weight(1f).height(28.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mono), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(source.mono, { m -> onChange(source.with { mono = m }) })
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) }
        onMerge?.let { TextButton(onClick = it) { Text(stringResource(R.string.merge_into_desktop)) } }
    }
}

@Composable
private fun Meter(needle: Needle?, dimmed: Boolean) {
    val idle = MaterialTheme.colorScheme.outline
    val holdColor = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        fun x(db: Float) = size.width * (db.coerceIn(METER_MIN_DB, 0f) - METER_MIN_DB) / -METER_MIN_DB
        val rms = x(needle?.rms ?: METER_MIN_DB)
        val peak = x(needle?.peak ?: METER_MIN_DB)
        for ((range, zone) in ZONES) {
            val color = if (dimmed) idle else zone
            val (a, b) = x(range.first) to x(range.second)
            drawRect(color.copy(alpha = 0.16f), Offset(a, 0f), Size(b - a, size.height))
            val rmsEnd = min(rms, b)
            if (rmsEnd > a) drawRect(color, Offset(a, 0f), Size(rmsEnd - a, size.height))
            val peakStart = max(rms, a)
            val peakEnd = min(peak, b)
            if (peakEnd > peakStart) drawRect(color.copy(alpha = 0.5f), Offset(peakStart, 0f), Size(peakEnd - peakStart, size.height))
        }
        val hold = needle?.hold ?: METER_MIN_DB
        if (hold > METER_MIN_DB) {
            val w = 2.dp.toPx()
            drawRoundRect(holdColor, Offset((x(hold) - w).coerceAtLeast(0f), 0f), Size(w, size.height), CornerRadius(w / 2))
        }
    }
}

/** dB ticks under the meters, aligned with [Meter]. */
@Composable
internal fun MeterScale() {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        for (db in SCALE) {
            val layout = measurer.measure(db.toString(), style)
            val cx = size.width * (db - METER_MIN_DB) / -METER_MIN_DB
            val left = (cx - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(left, 0f))
        }
    }
}

private fun formatDb(db: Float): String =
    if (db <= METER_MIN_DB) "−∞" else String.format(Locale.ROOT, "%.1f", db).replace('-', '−')

internal fun formatGain(db: Float): String =
    String.format(Locale.ROOT, "%+.1f", db).replace('-', '−')

/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.core

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

/** What one video encoder can do, read from MediaCodecInfo (or faked in tests). */
data class VideoCaps(
    val widthAlign: Int,
    val heightAlign: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    val minKbps: Int,
    val maxKbps: Int,
    /** Macroblocks per second at [blockSize]; 0 when unknown. */
    val maxBlocksPerSecond: Long,
    val blockSize: Int = 16,
)

/** The screen being recorded: its size in its current rotation and the panel's refresh rate now. */
data class Screen(val width: Int, val height: Int, val refreshHz: Float)

/** Everything the engine configures, already within the hardware's limits. */
data class EncoderPlan(
    val videoCodec: Int,
    val width: Int,
    val height: Int,
    /** Capture rate: an even share of the panel's refresh (see [fps]), so it is never fractional by accident. */
    val fps: Float,
    val videoBps: Int,
    val rateControl: Int,
    val keyframeSeconds: Float,
    /** QP bounds for [Settings.RC_QUALITY]; null otherwise. */
    val qp: IntRange?,
    val audioCodec: Int,
    /** 0 for FLAC (lossless). */
    val audioBps: Int,
    /** The hardware AAC encoder handles this bitrate. */
    val audioHardware: Boolean,
) {
    /** "60" or "67.5". */
    val fpsLabel: String get() = if (fps % 1f == 0f) fps.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", fps)

    companion object {
        /** Kbps at 1920×1080×60 that each preset scales from. */
        private fun baseKbps(preset: Int) = when (preset) {
            Settings.PRESET_QUALITY -> 40_000
            Settings.PRESET_EDITING -> 50_000
            else -> 20_000
        }

        private const val REF_PIXELS = 1920.0 * 1080
        private const val REF_FPS = 60.0
        /** Frames share most of their content: doubling the rate needs about 1.5× the bits. */
        private const val FPS_EXPONENT = 0.6
        /** Small is a size target, not a quality one: a flat bitrate. */
        private const val SMALL_KBPS = 4_000
        const val HW_AAC_MAX_KBPS = 192
        private val QUALITY_QP = 18..30

        /**
         * Resolves [s] for [screen] with the encoder [caps]: never upscales, aligns the size, keeps
         * fps within the screen and the encoder's block rate, and clamps every bitrate.
         */
        fun resolve(s: Settings, screen: Screen, caps: VideoCaps): EncoderPlan {
            val (w, h) = size(s.shortSide, screen, caps)
            val fps = fps(s.fps, w, h, screen, caps)
            val kbps = when {
                s.videoKbps > 0 -> s.videoKbps
                s.preset == Settings.PRESET_SMALL -> SMALL_KBPS
                else -> (baseKbps(s.preset) * w.toDouble() * h / REF_PIXELS * (fps / REF_FPS).pow(FPS_EXPONENT)).roundToInt()
            }
            val videoKbps = kbps.coerceIn(Settings.MIN_VIDEO_KBPS, Settings.MAX_VIDEO_KBPS)
                .coerceIn(caps.minKbps, maxOf(caps.minKbps, caps.maxKbps))
            val audioKbps = s.audioKbps.coerceIn(Settings.MIN_AUDIO_KBPS, Settings.MAX_AUDIO_KBPS)
            return EncoderPlan(
                videoCodec = s.videoCodec,
                width = w,
                height = h,
                fps = fps,
                videoBps = videoKbps * 1000,
                rateControl = s.rateControl,
                keyframeSeconds = s.keyframeMs.coerceIn(500, 10_000) / 1000f,
                qp = if (s.rateControl == Settings.RC_QUALITY) QUALITY_QP else null,
                audioCodec = s.audioCodec,
                audioBps = if (s.audioCodec == Settings.AUDIO_FLAC) 0 else audioKbps * 1000,
                audioHardware = s.audioCodec == Settings.AUDIO_AAC && audioKbps <= HW_AAC_MAX_KBPS,
            )
        }

        /** The screen scaled so its short side is [shortSide] (0 = native), aligned, within caps. */
        internal fun size(shortSide: Int, screen: Screen, caps: VideoCaps): Pair<Int, Int> {
            val native = minOf(screen.width, screen.height)
            val target = if (shortSide <= 0) native else minOf(shortSide, native)
            var scale = target.toDouble() / native
            // The encoder's limits apply to its own orientation; screens are tall, encoders wide.
            val long = maxOf(screen.width, screen.height)
            val capLong = maxOf(caps.maxWidth, caps.maxHeight)
            val capShort = minOf(caps.maxWidth, caps.maxHeight)
            scale = minOf(scale, capLong.toDouble() / long, capShort.toDouble() / native)
            val align = maxOf(2, caps.widthAlign, caps.heightAlign)
            fun fit(v: Int) = maxOf(align, (v * scale).toInt() / align * align)
            return fit(screen.width) to fit(screen.height)
        }

        /**
         * The capture rate for [want] fps. A mirror is composed on the panel's vsync, so only
         * refresh/k gives an even cadence; anything else drops frames unevenly (60 on a 135 Hz
         * panel loses one in nine and stutters). Picks the lowest such rate no more than 5 % under
         * [want] (the panel's own rate if [want] is above it), then the highest one the encoder's
         * block rate sustains.
         */
        internal fun fps(want: Int, w: Int, h: Int, screen: Screen, caps: VideoCaps): Float {
            val rates = (1..MAX_DIVISOR).map { screen.refreshHz / it }
            var fps = if (want <= Settings.FPS_DISPLAY) rates.first() else rates.filter { it >= want * 0.95f }.minOrNull() ?: rates.first()
            if (caps.maxBlocksPerSecond > 0) {
                val blocks = ceil(w / caps.blockSize.toDouble()) * ceil(h / caps.blockSize.toDouble())
                val cap = (caps.maxBlocksPerSecond / blocks).toFloat()
                if (fps > cap) fps = rates.firstOrNull { it <= cap } ?: rates.last()
            }
            return fps
        }

        private const val MAX_DIVISOR = 8
    }
}

/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.core

/**
 * What the user asked for. Zero means "automatic" for [videoKbps] and [shortSide], and "the
 * display's own rate" for [fps]. The engine never uses these directly: [EncoderPlan.resolve]
 * turns them into what the hardware can do.
 */
data class Settings(
    val preset: Int = PRESET_BALANCED,
    val videoCodec: Int = VIDEO_HEVC,
    val rateControl: Int = RC_VBR,
    val videoKbps: Int = 0,
    val shortSide: Int = 0,
    val fps: Int = FPS_DISPLAY,
    val keyframeMs: Int = 2000,
    val audioCodec: Int = AUDIO_AAC,
    val audioKbps: Int = 256,
) {
    /** The preset's values, keeping [fps] unless the preset fixes it. */
    fun withPreset(p: Int): Settings = when (p) {
        PRESET_QUALITY -> Settings(preset = p, fps = fps, audioCodec = audioCodec, audioKbps = 320)
        PRESET_SMALL -> Settings(preset = p, shortSide = 720, fps = 30, audioKbps = 128)
        PRESET_EDITING -> Settings(preset = p, videoCodec = VIDEO_AVC, rateControl = RC_CBR, keyframeMs = 1000, fps = fps, audioCodec = audioCodec)
        PRESET_BALANCED -> Settings(preset = p, fps = fps, audioCodec = audioCodec)
        else -> copy(preset = PRESET_CUSTOM)
    }

    companion object {
        const val PRESET_BALANCED = 0
        const val PRESET_QUALITY = 1
        const val PRESET_SMALL = 2
        const val PRESET_EDITING = 3
        const val PRESET_CUSTOM = 4

        const val VIDEO_HEVC = 0
        const val VIDEO_AVC = 1

        const val RC_VBR = 0
        const val RC_CBR = 1
        /** VBR held between QP bounds: steady quality, size follows the content. */
        const val RC_QUALITY = 2

        const val AUDIO_AAC = 0
        const val AUDIO_OPUS = 1
        const val AUDIO_FLAC = 2

        /** Every frame the display composes: the smoothest, never resampled. */
        const val FPS_DISPLAY = 0
        val FPS_CHOICES = intArrayOf(FPS_DISPLAY, 30, 60, 90, 120)
        val SHORT_SIDES = intArrayOf(0, 1440, 1080, 720)
        val KEYFRAME_MS = intArrayOf(500, 1000, 2000, 5000, 10_000)
        const val MIN_VIDEO_KBPS = 1_000
        const val MAX_VIDEO_KBPS = 160_000
        const val MIN_AUDIO_KBPS = 64
        const val MAX_AUDIO_KBPS = 512
    }
}

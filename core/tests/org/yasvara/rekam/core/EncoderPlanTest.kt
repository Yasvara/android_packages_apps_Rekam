/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.math.pow
import org.junit.Test

class EncoderPlanTest {
    /** A 1280×2772 panel at 120 Hz, c2.qti.hevc.encoder as MediaCodecInfo reports it. */
    private val onyx = Screen(1280, 2772, 120f)
    private val hevc = VideoCaps(2, 2, 8192, 4320, 1, 160_000, 2_937_600)
    private val avc = VideoCaps(2, 2, 4096, 2176, 1, 160_000, 1_958_400)

    @Test fun balancedIsNativeAt20MbpsPer1080p60() {
        val p = EncoderPlan.resolve(Settings(fps = 60), onyx, hevc)
        assertEquals(1280 to 2772, p.width to p.height)
        assertEquals(60f, p.fps)
        assertEquals("60", p.fpsLabel)
        assertEquals(20_000 * 1280.0 * 2772 / (1920 * 1080), p.videoBps / 1000.0, 1.0)
        assertNull(p.qp)
        assertEquals(2f, p.keyframeSeconds)
    }

    @Test fun neverUpscales() {
        val p = EncoderPlan.resolve(Settings(shortSide = 1440), onyx, hevc)
        assertEquals(1280 to 2772, p.width to p.height)
    }

    @Test fun downscalesKeepingAspectAndAlignment() {
        val p = EncoderPlan.resolve(Settings(shortSide = 720), onyx, hevc)
        assertEquals(720, p.width)
        assertEquals(1558, p.height) // 2772 × 720/1280 = 1559.25 → even
        assertTrue(p.width % 2 == 0 && p.height % 2 == 0)
    }

    @Test fun landscapeScreensScaleTheShortSide() {
        val p = EncoderPlan.resolve(Settings(shortSide = 1080), Screen(2772, 1280, 120f), hevc)
        assertEquals(2338 to 1080, p.width to p.height)
    }

    @Test fun fpsIsCappedByTheScreen() {
        assertEquals(120f, EncoderPlan.resolve(Settings(fps = 240), onyx, hevc).fps)
        assertEquals(60f, EncoderPlan.resolve(Settings(fps = 120), Screen(1080, 2400, 60f), hevc).fps)
    }

    /** onyx with its panel locked at 135 Hz: only 135/k composes evenly. */
    @Test fun fpsIsAnEvenShareOfTheRefresh() {
        val panel = Screen(1280, 2772, 135f)
        fun fps(want: Int) = EncoderPlan.resolve(Settings(fps = want), panel, hevc)
        assertEquals(67.5f, fps(60).fps)
        assertEquals("67.5", fps(60).fpsLabel)
        assertEquals(33.75f, fps(30).fps)
        assertEquals("near 90 nothing even is lower than 135", 135f, fps(90).fps)
        assertEquals(135f, fps(120).fps)
        assertEquals(45f, fps(45).fps)
        assertEquals("above the panel: the panel", 60f, EncoderPlan.resolve(Settings(fps = 64), Screen(1280, 2772, 60f), hevc).fps)
        assertEquals("5 % under is close enough", 57.6f, EncoderPlan.resolve(Settings(fps = 60), Screen(1280, 2772, 288f), hevc).fps)
    }

    @Test fun fpsIsCappedByTheEncoderBlockRate() {
        // 80 × 174 blocks at native: 2 937 600 / 13 920 = 211 fps for HEVC, 140 for AVC's lower
        // ceiling; a slow encoder far less.
        val slow = hevc.copy(maxBlocksPerSecond = 13_920L * 45)
        assertEquals("the highest even rate under 45", 40f, EncoderPlan.resolve(Settings(fps = 120), onyx, slow).fps)
        assertEquals(120f, EncoderPlan.resolve(Settings(fps = 120, videoCodec = Settings.VIDEO_AVC), onyx, avc).fps)
    }

    @Test fun encoderSizeLimitsShrinkTheOutput() {
        val small = hevc.copy(maxWidth = 1920, maxHeight = 1080)
        val p = EncoderPlan.resolve(Settings(), onyx, small)
        assertTrue("${p.width}x${p.height}", p.height <= 1920 && p.width <= 1080)
        assertEquals(p.width.toDouble() / p.height, 1280.0 / 2772, 0.01)
    }

    @Test fun displayRateIsTheDefault() {
        assertEquals(120f, EncoderPlan.resolve(Settings(), onyx, hevc).fps)
        assertEquals(135f, EncoderPlan.resolve(Settings(), Screen(1280, 2772, 135f), hevc).fps)
    }

    @Test fun autoBitrateScalesWithPixelsAndFps() {
        val p30 = EncoderPlan.resolve(Settings(fps = 30), onyx, hevc)
        val p60 = EncoderPlan.resolve(Settings(fps = 60), onyx, hevc)
        assertEquals("twice the rate, 2^0.6 the bits", p60.videoBps / 2.0.pow(0.6), p30.videoBps.toDouble(), 1000.0)
        val q = EncoderPlan.resolve(Settings(fps = 60).withPreset(Settings.PRESET_QUALITY), onyx, hevc)
        assertEquals(p60.videoBps * 2.0, q.videoBps.toDouble(), 1000.0)
    }

    @Test fun bitratesAreClamped() {
        assertEquals(160_000_000, EncoderPlan.resolve(Settings(videoKbps = 999_999), onyx, hevc).videoBps)
        assertEquals(1_000_000, EncoderPlan.resolve(Settings(videoKbps = 5), onyx, hevc).videoBps)
        assertEquals(50_000_000, EncoderPlan.resolve(Settings(videoKbps = 80_000), onyx, hevc.copy(maxKbps = 50_000)).videoBps)
        assertEquals(512_000, EncoderPlan.resolve(Settings(audioKbps = 9_000), onyx, hevc).audioBps)
        assertEquals(64_000, EncoderPlan.resolve(Settings(audioKbps = 1), onyx, hevc).audioBps)
    }

    @Test fun presets() {
        val small = EncoderPlan.resolve(Settings().withPreset(Settings.PRESET_SMALL), onyx, hevc)
        assertEquals(720 to 30f, small.width to small.fps)
        assertEquals(4_000_000, small.videoBps)
        val edit = Settings(fps = 90).withPreset(Settings.PRESET_EDITING)
        assertEquals(Settings.VIDEO_AVC, edit.videoCodec)
        assertEquals(Settings.RC_CBR, edit.rateControl)
        assertEquals(1000, edit.keyframeMs)
        assertEquals(90, edit.fps)
        assertEquals(Settings.PRESET_CUSTOM, Settings(videoKbps = 12_000).withPreset(Settings.PRESET_CUSTOM).preset)
        assertEquals(12_000, Settings(videoKbps = 12_000).withPreset(Settings.PRESET_CUSTOM).videoKbps)
    }

    @Test fun qualityModeHasQpBounds() {
        val p = EncoderPlan.resolve(Settings(rateControl = Settings.RC_QUALITY), onyx, hevc)
        assertEquals(18..30, p.qp)
    }

    @Test fun audioEncoderChoice() {
        assertTrue(EncoderPlan.resolve(Settings(audioKbps = 192), onyx, hevc).audioHardware)
        assertFalse(EncoderPlan.resolve(Settings(audioKbps = 256), onyx, hevc).audioHardware)
        val flac = EncoderPlan.resolve(Settings(audioCodec = Settings.AUDIO_FLAC), onyx, hevc)
        assertEquals(0, flac.audioBps)
        assertFalse(flac.audioHardware)
        assertFalse(EncoderPlan.resolve(Settings(audioCodec = Settings.AUDIO_OPUS, audioKbps = 128), onyx, hevc).audioHardware)
    }

    @Test fun keyframeIntervalIsClamped() {
        assertEquals(0.5f, EncoderPlan.resolve(Settings(keyframeMs = 1), onyx, hevc).keyframeSeconds)
        assertEquals(10f, EncoderPlan.resolve(Settings(keyframeMs = 99_000), onyx, hevc).keyframeSeconds)
    }
}

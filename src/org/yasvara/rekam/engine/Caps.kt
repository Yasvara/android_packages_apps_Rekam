/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.DisplayMetrics
import android.view.Display
import org.yasvara.rekam.core.EncoderPlan
import org.yasvara.rekam.core.Screen
import org.yasvara.rekam.core.Settings
import org.yasvara.rekam.core.VideoCaps

/** The device side of [EncoderPlan.resolve]: the codecs Rekam uses and what they report. */
internal object Caps {
    fun videoCodec(codec: Int) = if (codec == Settings.VIDEO_AVC) "c2.qti.avc.encoder" else "c2.qti.hevc.encoder"

    fun videoMime(codec: Int) = if (codec == Settings.VIDEO_AVC) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC

    private val list by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }

    fun video(codec: Int): VideoCaps {
        val name = videoCodec(codec)
        val vc = checkNotNull(list.first { it.name == name }.getCapabilitiesForType(videoMime(codec)).videoCapabilities) { name }
        // The block rate, from the frame rate a large size allows (small sizes hit the fps cap).
        val (rw, rh) = listOf(3840 to 2160, 1920 to 1080).first { (w, h) -> vc.isSizeSupported(w, h) }
        val fps = vc.getSupportedFrameRatesFor(rw, rh).upper
        val blocks = ((rw + BLOCK - 1) / BLOCK).toLong() * ((rh + BLOCK - 1) / BLOCK)
        return VideoCaps(
            widthAlign = vc.widthAlignment,
            heightAlign = vc.heightAlignment,
            maxWidth = vc.supportedWidths.upper,
            maxHeight = vc.supportedHeights.upper,
            minKbps = vc.bitrateRange.lower / 1000,
            maxKbps = vc.bitrateRange.upper / 1000,
            maxBlocksPerSecond = (fps * blocks).toLong(),
            blockSize = BLOCK,
        )
    }

    private const val BLOCK = 16

    fun hasCodec(name: String) = list.any { it.name == name }

    /** The default display as it is rotated now, and the refresh rate the panel runs at. */
    fun screen(context: Context): Screen {
        val d = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics().also { d.getRealMetrics(it) }
        return Screen(m.widthPixels, m.heightPixels, d.refreshRate)
    }

    /** The recorder's codec ids for [plan]'s tracks: video, then audio. */
    fun muxerCodecs(plan: EncoderPlan) = intArrayOf(
        if (plan.videoCodec == Settings.VIDEO_AVC) Native.AVC else Native.HEVC,
        when (plan.audioCodec) {
            Settings.AUDIO_OPUS -> Native.OPUS
            Settings.AUDIO_FLAC -> Native.FLAC
            else -> Native.AAC
        },
    )

    fun plan(context: Context, s: Settings): EncoderPlan = EncoderPlan.resolve(s, screen(context), video(s.videoCodec))
}

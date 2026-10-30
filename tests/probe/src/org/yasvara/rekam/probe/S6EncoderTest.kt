/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.probe

import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
import android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
import android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** S6: what the c2.qti video encoders really accept on onyx. */
@RunWith(AndroidJUnit4::class)
class S6EncoderTest {
    private val hevc = "c2.qti.hevc.encoder"
    private val avc = "c2.qti.avc.encoder"
    private val w = 1080
    private val h = 2400

    private fun log(line: String) = Probe.log("S6", line)

    @Test
    fun caps() {
        val names = listOf(hevc, avc, "c2.qti.hevc.encoder.hdr", "c2.android.av1.encoder", "c2.android.apv.encoder",
            "c2.android.hevc.encoder", "c2.qti.aac.hw.encoder", "c2.android.aac.encoder", "c2.android.opus.encoder",
            "c2.android.flac.encoder")
        val all = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.associateBy { it.name }
        for (n in names) {
            val info = all[n] ?: run { log("caps $n MISSING"); continue }
            for (type in info.supportedTypes) {
                val c = info.getCapabilitiesForType(type)
                val features = listOf(
                    CodecCapabilities.FEATURE_IntraRefresh, CodecCapabilities.FEATURE_QpBounds,
                    CodecCapabilities.FEATURE_EncodingStatistics, CodecCapabilities.FEATURE_HdrEditing,
                    CodecCapabilities.FEATURE_MultipleFrames,
                ).filter { c.isFeatureSupported(it) }
                val enc = c.encoderCapabilities
                val modes = listOf(BITRATE_MODE_CQ to "CQ", BITRATE_MODE_VBR to "VBR", BITRATE_MODE_CBR to "CBR")
                    .filter { enc?.isBitrateModeSupported(it.first) == true }.map { it.second }
                val v = c.videoCapabilities
                val a = c.audioCapabilities
                log(buildString {
                    append("caps $n $type hw=${info.isHardwareAccelerated} features=$features modes=$modes")
                    append(" complexity=${enc?.complexityRange} quality=${enc?.qualityRange}")
                    append(" profiles=${c.profileLevels.map { it.profile }.distinct()}")
                    if (v != null) {
                        append(" bitrate=${v.bitrateRange} fpsAt${w}x$h=${runCatching { v.getSupportedFrameRatesFor(w, h) }.getOrNull()}")
                        append(" achievable=${runCatching { v.getAchievableFrameRatesFor(w, h) }.getOrNull()}")
                    }
                    if (a != null) append(" bitrate=${a.bitrateRange} rates=${a.supportedSampleRateRanges.toList()} ch=${a.maxInputChannelCount}")
                })
            }
        }
    }

    @Test
    fun bFrames() {
        for (name in listOf(hevc, avc)) {
            val mime = if (name == hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            for (b in 0..2) {
                val fmt = videoFormat(mime, w, h, 60, 20_000_000, BITRATE_MODE_VBR).apply {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, b)
                    if (name == avc) setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.AVCProfileHigh)
                }
                val r = encodeSurface(name, fmt, 120, 60)
                log("bframes $name b=$b ${r.summary()} outMaxB=${r.format?.let { f -> if (f.containsKey(MediaFormat.KEY_MAX_B_FRAMES)) f.getInteger(MediaFormat.KEY_MAX_B_FRAMES) else null }}")
            }
        }
    }

    @Test
    fun qpBoundsAndStats() {
        for ((lo, hi) in listOf(-1 to -1, 22 to 30, 35 to 45)) {
            val fmt = videoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h, 60, 20_000_000, BITRATE_MODE_VBR).apply {
                setInteger(MediaFormat.KEY_VIDEO_ENCODING_STATISTICS_LEVEL, MediaFormat.VIDEO_ENCODING_STATISTICS_LEVEL_1)
                if (lo >= 0) { setInteger(MediaFormat.KEY_VIDEO_QP_MIN, lo); setInteger(MediaFormat.KEY_VIDEO_QP_MAX, hi) }
            }
            val r = encodeSurface(hevc, fmt, 120, 60)
            val withQp = r.frames.count { it.qp >= 0 }
            log("qp bounds=$lo..$hi ${r.summary()} framesWithStats=$withQp/${r.frames.size} picTypes=${r.frames.map { it.picType }.distinct()}")
        }
    }

    @Test
    fun rates() {
        for (name in listOf(hevc, avc)) {
            val mime = if (name == hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            for ((mode, label) in listOf(BITRATE_MODE_CBR to "CBR", BITRATE_MODE_VBR to "VBR")) {
                for (fps in listOf(60, 120)) {
                    val r = encodeSurface(name, videoFormat(mime, w, h, fps, 40_000_000, mode), fps * 3, fps)
                    val sizes = r.frames.map { it.size.toLong() }
                    log("rate $name $label ${fps}fps target=40 ${r.summary()} maxFrameKB=${(sizes.maxOrNull() ?: 0) / 1024}")
                }
            }
            // Native panel size; the VPU rejects a rate it can't sustain, so step it up paced.
            for (fps in listOf(120, 144, 165, 240)) {
                val r = encodeSurface(name, videoFormat(mime, 1280, 2772, fps, 40_000_000, BITRATE_MODE_VBR), fps * 2, fps)
                log("native $name 1280x2772@$fps ${r.summary()}")
            }
        }
    }

    @Test
    fun burst() {
        for (cap in listOf(0f, 120f)) {
            val fmt = videoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h, 60, 20_000_000, BITRATE_MODE_VBR).apply {
                if (cap > 0) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, cap)
            }
            log("burst unpaced maxFps=$cap ${encodeSurface(hevc, fmt, 120, 60, paced = false).summary()}")
        }
    }

    /** Real VPU capacity (2-pipe Iris3): which size × fps the firmware accepts when declared up front. */
    @Test
    fun capacity() {
        val grid = listOf(1920 to 1080 to listOf(240, 300), 1080 to 2400 to listOf(240, 300),
            1280 to 2772 to listOf(180, 200, 220), 3840 to 2160 to listOf(60, 90, 120))
        for ((size, rates) in grid) for (fps in rates) {
            val (gw, gh) = size
            val r = encodeSurface(hevc, videoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, gw, gh, fps, 40_000_000, BITRATE_MODE_VBR), fps * 2, fps)
            log("capacity ${gw}x$gh@$fps mbps=${(gw + 15) / 16 * ((gh + 15) / 16) * fps} ${r.summary()}")
        }
    }

    @Test
    fun main10() {
        for (name in listOf(hevc, "c2.qti.hevc.encoder.hdr")) {
            val fmt = videoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h, 60, 20_000_000, BITRATE_MODE_VBR).apply {
                setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            }
            val r = encodeSurface(name, fmt, 60, 60)
            log("main10 $name ${r.summary()} outProfile=${r.format?.let { if (it.containsKey(MediaFormat.KEY_PROFILE)) it.getInteger(MediaFormat.KEY_PROFILE) else null }} outColor=${r.format?.let { if (it.containsKey(MediaFormat.KEY_COLOR_FORMAT)) it.getInteger(MediaFormat.KEY_COLOR_FORMAT) else null }}")
        }
    }

    @Test
    fun pacingKeys() {
        val capped = videoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h, 60, 20_000_000, BITRATE_MODE_VBR).apply {
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 60f)
        }
        log("maxFps60 fed120 ${encodeSurface(hevc, capped, 240, 120).summary()}")
        val repeat = videoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h, 60, 20_000_000, BITRATE_MODE_VBR).apply {
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / 60)
        }
        log("repeatPrev fed10 ${encodeSurface(hevc, repeat, 20, 10).summary()}")
    }

    @Test
    fun liveParams() {
        val fmt = videoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h, 60, 40_000_000, BITRATE_MODE_CBR, gopSec = 10f)
        val r = encodeSurface(hevc, fmt, 360, 60) { i, c ->
            if (i == 180) c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, 10_000_000) })
            if (i == 90) c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
        if (r.error != null) { log("live ${r.summary()}"); return }
        val p0 = r.frames.minOf { it.ptsUs }
        val mid = p0 + 3_000_000
        val keyPts = r.frames.filter { it.key }.map { (it.ptsUs - p0) / 1000 }
        log("live bitrate 40→10 at 3s, sync request at 1.5s: first=%.2f second=%.2f keysMs=%s".format(r.mbps(toPts = mid), r.mbps(fromPts = mid + 500_000), keyPts))
    }
}

/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.probe

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S3: consent-free projection per target and its latency to the first frame; a task capture must
 * not contain the probe's overlay. S4: what a below-overlay session leaves out.
 * Frames are analysed in memory only; nothing of the user's screen is stored.
 */
@RunWith(AndroidJUnit4::class)
class S3S4ProjectionTest {
    @Before fun up() = Proj.startFgs()
    @After fun down() = Proj.stopFgs()

    @Test
    fun s3PerTargetLatency() {
        val (task, pkg) = Proj.topTask() ?: run { Probe.log("S3", "no visible task"); return }
        val remove = Proj.overlay()
        try {
            for (i in 0 until 5) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                val p = Proj.create(taskId = task)
                val createMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
                val (first, v) = Proj.firstFrame(p) { "overlayMagentaPct=%.1f".format(Proj.magentaPct(it)) }
                p.stop()
                Probe.log("S3", "task=$task iter=$i createMs=$createMs firstFrameMs=$first $v")
            }
            val p = Proj.create()
            val (first, v) = Proj.firstFrame(p) { "overlayMagentaPct=%.1f".format(Proj.magentaPct(it)) }
            p.stop()
            Probe.log("S3", "display firstFrameMs=$first $v")
        } finally { remove() }
    }

    @Test
    fun s4BelowOverlay() {
        val remove = Proj.overlay()
        val tops = HashMap<String, IntArray>()
        try {
            for ((label, overlay) in listOf("display" to false, "belowOverlay" to true)) {
                val p = Proj.create(overlay = overlay)
                val (first, v) = Proj.firstFrame(p, scale = 4) { b ->
                    tops[label] = topStrip(b)
                    "overlayMagentaPct=%.1f topStripLuma=%d".format(Proj.magentaPct(b), tops[label]!!.average().toInt())
                }
                p.stop()
                Probe.log("S4", "$label firstFrameMs=$first $v")
            }
            val a = tops["display"]; val b = tops["belowOverlay"]
            if (a != null && b != null) Probe.log("S4", "statusBarStripMeanAbsDiff=%.1f".format(a.indices.map { kotlin.math.abs(a[it] - b[it]) }.average()))
        } finally { remove() }
    }

    /** Luma of the top 3 % of rows (the status bar), column-averaged. */
    private fun topStrip(b: Bitmap): IntArray {
        val rows = (b.height * 0.03).toInt().coerceAtLeast(1)
        return IntArray(b.width) { x ->
            var sum = 0
            for (y in 0 until rows) { val c = b.getPixel(x, y); sum += (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10 }
            sum / rows
        }
    }
}

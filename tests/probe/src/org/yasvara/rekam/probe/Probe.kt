/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Result sink: logcat tag RekamProbe plus one text file per spike under files/probe/. */
object Probe {
    val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dir by lazy { File(ctx.getExternalFilesDir(null), "probe").apply { mkdirs() } }

    fun log(spike: String, line: String) {
        Log.i("RekamProbe", "$spike $line")
        File(dir, "$spike.txt").appendText(line + "\n")
    }
}

fun LongArray.pct(p: Double): Long {
    if (isEmpty()) return 0
    val s = sortedArray()
    return s[((s.size - 1) * p).toInt()]
}

fun LongArray.ms(p: Double): String = "%.2f".format(pct(p) / 1e6)

/** Moving, detailed content so encoders do real work: 48 drifting rects, a sweep bar, a counter. */
class Pattern(private val w: Int, private val h: Int) {
    private val paint = Paint().apply { isAntiAlias = false; textSize = 72f }

    fun draw(c: Canvas, i: Int) {
        c.drawColor(Color.rgb((i * 3) and 0xff, 40, 90))
        for (k in 0 until 48) {
            val speed = (k % 5 + 1) * 7
            val x = ((k * 97 + i * speed) % w).toFloat()
            val y = ((k * 233 + i * (speed / 2 + 1)) % h).toFloat()
            paint.color = Color.rgb((k * 53 + i) and 0xff, (k * 29) and 0xff, (k * 71 + i * 2) and 0xff)
            c.drawRect(x, y, x + 60f + k * 4, y + 40f + k * 3, paint)
        }
        paint.color = Color.WHITE
        val bar = ((i * 16) % w).toFloat()
        c.drawRect(bar, 0f, bar + 24f, h.toFloat(), paint)
        c.drawText("frame $i", 48f, 160f, paint)
    }
}

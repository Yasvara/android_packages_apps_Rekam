/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.ui

import android.app.ActivityTaskManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.window.TaskSnapshotManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** An open app task, as the recents carousel shows it; [visible] ones are on screen now. */
internal class AppTask(val taskId: Int, val pkg: String, val label: String, val icon: ImageBitmap, val visible: Boolean)

/** Open app tasks, most recent first, without the launcher, SystemUI and Rekam. */
internal fun openTasks(context: Context): List<AppTask> {
    val pm = context.packageManager
    val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    val skip = setOf(home, context.packageName, "com.android.systemui")
    return runCatching { ActivityTaskManager.getService().getTasks(MAX_TASKS, false, true, -1) }.getOrDefault(emptyList())
        .mapNotNull { t ->
            val pkg = t.baseActivity?.packageName ?: return@mapNotNull null
            if (pkg in skip) return@mapNotNull null
            runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                AppTask(t.taskId, pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai).toBitmap(ICON_PX, ICON_PX).asImageBitmap(), t.isVisible)
            }.getOrNull()
        }
}

/**
 * What the task shows: a fresh capture when it is on screen ([live]), otherwise the snapshot
 * recents keeps, or a fresh one if it has none. Null if the task has nothing to show.
 */
private fun snapshot(taskId: Int, live: Boolean): ImageBitmap? = runCatching {
    val m = TaskSnapshotManager.getInstance()
    val s = (if (live) null else m.getTaskSnapshot(taskId, TaskSnapshotManager.RESOLUTION_ANY))
        ?: m.takeTaskSnapshot(taskId, false)
    s?.wrapToBitmap()?.asImageBitmap()
}.onFailure { Log.w("Rekam", "task $taskId snapshot", it) }.getOrNull()

@Composable
internal fun TaskCarousel(tasks: List<AppTask>, selected: Int, onSelect: (AppTask) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        items(tasks, key = { it.taskId }) { t -> TaskCard(t, t.taskId == selected) { onSelect(t) } }
    }
}

@Composable
private fun TaskCard(task: AppTask, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val thumb by produceState<ImageBitmap?>(null, task.taskId) {
        do {
            withContext(Dispatchers.IO) { snapshot(task.taskId, live = task.visible) }?.let { value = it }
            delay(LIVE_MS)
        } while (task.visible)
    }
    val shape = RoundedCornerShape(16.dp)
    // Landscape games get a wide card, like recents.
    val aspect = thumb?.let { it.width.toFloat() / it.height } ?: PORTRAIT
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(task.icon, null, Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.height(CARD_HEIGHT).aspectRatio(aspect)
                .clip(shape)
                .background(cs.surfaceContainerHighest)
                .border(if (selected) 3.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant, shape)
                .clickable(onClick = onClick),
        ) {
            thumb?.let { Image(it, task.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            task.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) cs.primary else cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(CARD_HEIGHT * PORTRAIT),
        )
    }
}

private const val MAX_TASKS = 12
/** Refresh of an on-screen task's thumbnail. */
private const val LIVE_MS = 500L
private const val ICON_PX = 96
private const val PORTRAIT = 9f / 19.5f
private val CARD_HEIGHT = 240.dp

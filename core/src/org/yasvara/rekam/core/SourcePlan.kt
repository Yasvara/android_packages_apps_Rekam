/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.core

/**
 * Which apps get their own mixer strip: every app that plays while the graph runs, and the
 * recorded app from the start. Desktop Audio (slot [DESKTOP]) captures everything else, so each
 * app is heard once. A strip stays while the graph runs, so a paused song keeps its fader, until
 * the user merges it back ([remove]).
 */
class SourcePlan(private val slots: Int, private val selfUid: Int) {
    /** uid → slot of every app strip. */
    private val apps = LinkedHashMap<Int, Int>()

    val appSlots: Map<Int, Int> get() = apps

    /** What Desktop Audio must not capture: every app with its own strip, and Rekam. */
    val excluded: Set<Int> get() = apps.keys + selfUid

    /**
     * Adds a strip for each of [uids] that has none and fits; returns the new (uid, slot)
     * pairs. Rekam itself and system uids (< 10000, shared with platform sounds) never get one.
     */
    fun add(uids: Collection<Int>): List<Pair<Int, Int>> {
        val added = ArrayList<Pair<Int, Int>>()
        for (uid in uids) {
            if (uid == selfUid || uid < FIRST_APP_UID || uid in apps) continue
            val slot = (FIRST_APP_SLOT until slots).firstOrNull { it !in apps.values } ?: break
            apps[uid] = slot
            added += uid to slot
        }
        return added
    }

    /** Gives [uid]'s audio back to Desktop Audio; frees its slot. */
    fun remove(uid: Int) {
        apps.remove(uid)
    }

    fun clear() = apps.clear()

    companion object {
        const val DESKTOP = 0
        const val MIC = 1
        const val FIRST_APP_SLOT = 2
        const val FIRST_APP_UID = 10_000
    }
}

/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam;

import org.yasvara.rekam.AudioSource;
import org.yasvara.rekam.RecordRequest;

/** Engine state; sent on every change and once a second while recording. */
parcelable RekamState {
    const int IDLE = 0;
    const int STARTING = 1;
    const int RECORDING = 2;
    const int STOPPING = 3;

    int phase = IDLE;
    /**
     * elapsedRealtime base of the recorded time: while running, recorded = now - sinceMs (pauses
     * move it forward). 0 when idle.
     */
    long sinceMs;
    boolean paused;
    /** Recorded time, pauses excluded, as of this state. */
    long recordedMs;
    long bytes;
    long durationUs;
    long dropped;
    /** Package being recorded, null for the entire screen. */
    @nullable String target;
    /** Last saved or recovered recording. */
    @nullable String lastUri;
    @nullable String error;
    /** The audio graph's strips; empty while it isn't running. */
    AudioSource[] sources;
    /** The settings Start and the tile use next. */
    @nullable RecordRequest last;
}

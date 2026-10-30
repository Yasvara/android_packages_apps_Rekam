/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam;

/**
 * A recording to start. The quality fields carry org.yasvara.rekam.core.Settings (same names,
 * same constants); the engine resolves them against the encoder's limits.
 */
parcelable RecordRequest {
    /** Package to record as an app (task capture); null records the entire screen. */
    @nullable String packageName;
    /** Task of that package; -1 picks its top task. */
    int taskId = -1;
    /** Unmute the microphone strip at the start. */
    boolean mic;
    /** Take the microphone and every quality field from the last recording (tile, Pentas). */
    boolean useLast;

    int preset;
    int videoCodec;
    int rateControl;
    /** 0: automatic for the size and rate. */
    int videoKbps;
    /** Short side of the output; 0: native. */
    int shortSide;
    /** 0: the display's own rate. */
    int fps = 0;
    int keyframeMs = 2000;
    int audioCodec;
    int audioKbps = 256;
}

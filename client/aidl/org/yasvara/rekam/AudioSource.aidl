/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam;

/** One mixer strip: fader, filters, mute. */
parcelable AudioSource {
    /** Every app without its own strip ("Desktop Audio"). */
    const int KIND_DESKTOP = 0;
    /** One app's playback, split out of Desktop Audio when it plays. */
    const int KIND_APP = 1;
    const int KIND_MIC = 2;
    /** The final mix written to the file, after the limiter. */
    const int KIND_MASTER = 3;

    /** Stable across sessions: "desktop", "mic", "master" or "app:<package>"; keys its settings. */
    String id;
    int kind;
    @nullable String packageName;
    /** Its levels in the meter page (IRekam.meters). */
    int slot;
    /** The fader: −60 (silent) … 0 dB. */
    float volumeDb;
    /** Gain filter: 0 … +30 dB on top of the fader. */
    float boostDb;
    boolean muted;
    boolean mono;
    /** −1 left … 1 right. */
    float balance;
}

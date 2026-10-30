/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam;

import android.os.SharedMemory;
import org.yasvara.rekam.AudioSource;
import org.yasvara.rekam.IRekamCallback;
import org.yasvara.rekam.RecordRequest;
import org.yasvara.rekam.RekamState;

/**
 * Rekam engine control. Callers need org.yasvara.rekam.permission.CONTROL.
 * Append new methods at the end: clients carry their own copy of this interface.
 */
interface IRekam {
    RekamState getState();
    /** Starts a recording; ignored while one is running. */
    void start(in RecordRequest request);
    void stop();
    void registerCallback(IRekamCallback callback);
    void unregisterCallback(IRekamCallback callback);

    /**
     * The meter page, read-only (org.yasvara.rekam.core.MeterReader): peak and RMS per
     * AudioSource.slot (the master's is last), updated every mixed block while the graph runs.
     */
    SharedMemory meters();
    /** Runs the audio graph (meters, strips) while [token] lives and [watching]; for visible UI. */
    void watchAudio(IBinder token, boolean watching);
    /** Applies a strip's fader, filters and mute (matched by id); they apply live and stick. */
    void setSource(in AudioSource source);
    /** Merges an app strip back into Desktop Audio; it stays merged when the app plays again. */
    void removeSource(String id);
    void setPaused(boolean paused);
}

/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam;

import org.yasvara.rekam.RekamState;

oneway interface IRekamCallback {
    void onState(in RekamState state);
}

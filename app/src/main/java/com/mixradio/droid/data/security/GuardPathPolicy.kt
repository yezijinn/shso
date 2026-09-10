// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.mixradio.droid.data.security

/** Pure policy for the PATH used by protected Root execution. */
object GuardPathPolicy {
    private const val FIXED_SYSTEM_PATH = "/sbin:/system/sbin:/system/bin:/system/xbin"

    fun prefixOrNull(securityLevel: Int, guardReady: Boolean): String? {
        if (securityLevel < SecurityLevels.STANDARD) return ""
        if (!guardReady) return null
        return "export PATH=${GuardModuleInstaller.GUARD_BIN_DIR}:$FIXED_SYSTEM_PATH && "
    }
}

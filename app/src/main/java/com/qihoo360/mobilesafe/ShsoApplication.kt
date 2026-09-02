// Copyright 2026, shso contributors
// SPDX-License-Identifier: Apache-2.0

package com.qihoo360.mobilesafe

import android.app.Application
import com.qihoo360.mobilesafe.data.AppSettings

class ShsoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.getInstance(this)
    }
}

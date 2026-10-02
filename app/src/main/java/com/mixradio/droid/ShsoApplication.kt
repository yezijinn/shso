// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid

import android.app.Application
import com.mixradio.droid.data.AppSettings

class ShsoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
        AppSettings.getInstance(this)
    }

    companion object {
        @Volatile
        lateinit var appContext: android.content.Context
            private set
    }
}

// Copyright 2026, shso contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.mixradio.droid

import android.app.Application
import com.mixradio.droid.data.AppSettings
import com.mixradio.droid.data.EmbeddedEnv
import com.mixradio.droid.data.EnvPack

class ShsoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
        AppSettings.getInstance(this)
        // 后台解压内置 POSIX 环境（bash / busybox / curl 及依赖库）并登记扩展环境包状态。
        // 首次启动做实际解压，之后命中版本标记直接返回；放到后台避免拖慢冷启动。
        // 扩展包状态标记含落盘判读，同样避主线程（原在此同步调用会随冷启动阻塞，见 BUG-PERF-254）。
        Thread {
            runCatching { EmbeddedEnv.ensure(this) }
            // 可选扩展环境包：仅标记是否已安装，不触发下载。
            runCatching { EnvPack.ensureLoaded(this) }
        }.apply { name = "shso-env-prepare"; isDaemon = true }.start()
    }

    companion object {
        @Volatile
        lateinit var appContext: android.content.Context
            private set
    }
}

/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.ui.page.main

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * 从通知进入模块首页时，待打开的一键刷新面板及其预先勾选的音乐 App。
 *
 * 只负责打开面板，不替用户执行刷新；首页消费后即清空，避免重复弹出。
 */
object OneTapRefreshRequest {
    const val EXTRA_PACKAGES = "com.juren233.hyperlyricsenhanced.extra.ONE_TAP_REFRESH_PACKAGES"

    private val pending = MutableStateFlow<List<String>?>(null)
    val pendingPackages: StateFlow<List<String>?> = pending.asStateFlow()

    fun handle(intent: Intent?) {
        val packages = intent?.getStringArrayExtra(EXTRA_PACKAGES) ?: return
        intent.removeExtra(EXTRA_PACKAGES)
        pending.value = packages.toList()
    }

    fun consume(): List<String>? = pending.getAndUpdate { null }
}

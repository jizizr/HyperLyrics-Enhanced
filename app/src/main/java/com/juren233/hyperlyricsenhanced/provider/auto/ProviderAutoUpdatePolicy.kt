/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider.auto

import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.RootConstants

/**
 * 自动更新的开关与节流决策。时间戳可注入以便单元测试。
 */
object ProviderAutoUpdatePolicy {

    /** 打开 App 触发的检查最小间隔；周期 Worker 不受该间隔限制。 */
    const val MIN_APP_OPEN_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

    fun isEnabled(): Boolean =
        PrefsBridge.getBoolean(
            RootConstants.KEY_PROVIDER_AUTO_UPDATE,
            RootConstants.DEFAULT_PROVIDER_AUTO_UPDATE,
        )

    fun shouldCheckOnAppOpen(
        nowMs: Long = System.currentTimeMillis(),
        lastCheckMs: Long = PrefsBridge.getLong(RootConstants.KEY_PROVIDER_AUTO_UPDATE_LAST_CHECK, 0L),
    ): Boolean = nowMs - lastCheckMs >= MIN_APP_OPEN_CHECK_INTERVAL_MS

    fun recordCheck(nowMs: Long = System.currentTimeMillis()) {
        PrefsBridge.putLong(RootConstants.KEY_PROVIDER_AUTO_UPDATE_LAST_CHECK, nowMs)
    }
}

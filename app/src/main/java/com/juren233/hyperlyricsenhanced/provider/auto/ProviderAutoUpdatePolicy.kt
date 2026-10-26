/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider.auto

import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.RootConstants

/**
 * 自动更新的间隔策略与节流决策。
 *
 * 用户选择的间隔只作用于「打开 App 时的检查节流」；后台周期任务固定为
 * 24 小时（国产 ROM 上常被推迟，打开 App 的检查才是实际主路径）。
 * 选择 24 小时表示完全交给后台周期，打开 App 不再额外触发检查。
 */
object ProviderAutoUpdatePolicy {

    /** 不开启自动更新。 */
    const val INTERVAL_OFF = 0

    /** 仅保留 24 小时后台周期，打开 App 不额外触发。 */
    const val INTERVAL_PERIODIC_ONLY = 24

    /** 可选间隔（小时），与设置页下拉顺序一一对应。 */
    val CHOICE_HOURS = listOf(INTERVAL_OFF, 1, 2, 6, 12, INTERVAL_PERIODIC_ONLY)

    /**
     * 单次调度内的最大尝试次数（含首次）。每次重试都要重新拉起后台进程并重新拉目录，
     * 上限取小值：放弃后等下一个周期或下次打开 App 再检查，比反复退避省电。
     */
    const val MAX_RUN_ATTEMPTS = 3

    /** [runAttemptCount] 从 0 起，已达上限时本轮不再尝试。 */
    fun shouldGiveUp(runAttemptCount: Int): Boolean = runAttemptCount >= MAX_RUN_ATTEMPTS

    fun intervalHours(): Int {
        val prefs = PrefsBridge.getPrefs()
        // 一次性迁移：旧的布尔开关 → 间隔偏好（关=0，开=默认 6 小时）。
        if (!prefs.contains(RootConstants.KEY_PROVIDER_AUTO_UPDATE_INTERVAL_HOURS) &&
            prefs.contains(RootConstants.KEY_PROVIDER_AUTO_UPDATE)
        ) {
            val migrated = if (prefs.getBoolean(RootConstants.KEY_PROVIDER_AUTO_UPDATE, true)) {
                RootConstants.DEFAULT_PROVIDER_AUTO_UPDATE_INTERVAL_HOURS
            } else {
                INTERVAL_OFF
            }
            PrefsBridge.putInt(RootConstants.KEY_PROVIDER_AUTO_UPDATE_INTERVAL_HOURS, migrated)
            prefs.edit().remove(RootConstants.KEY_PROVIDER_AUTO_UPDATE).apply()
            return migrated
        }
        val stored = PrefsBridge.getInt(
            RootConstants.KEY_PROVIDER_AUTO_UPDATE_INTERVAL_HOURS,
            RootConstants.DEFAULT_PROVIDER_AUTO_UPDATE_INTERVAL_HOURS,
        )
        return CHOICE_HOURS.firstOrNull { it == stored }
            ?: RootConstants.DEFAULT_PROVIDER_AUTO_UPDATE_INTERVAL_HOURS
    }

    fun putIntervalHours(hours: Int) {
        require(hours in CHOICE_HOURS) { "不支持的自动更新间隔: $hours" }
        PrefsBridge.putInt(RootConstants.KEY_PROVIDER_AUTO_UPDATE_INTERVAL_HOURS, hours)
    }

    fun isEnabled(): Boolean = intervalHours() != INTERVAL_OFF

    fun shouldCheckOnAppOpen(
        intervalHours: Int = intervalHours(),
        nowMs: Long = System.currentTimeMillis(),
        lastCheckMs: Long = PrefsBridge.getLong(
            RootConstants.KEY_PROVIDER_AUTO_UPDATE_LAST_CHECK,
            0L,
        ),
    ): Boolean = intervalHours != INTERVAL_OFF &&
        intervalHours != INTERVAL_PERIODIC_ONLY &&
        nowMs - lastCheckMs >= intervalHours * 3_600_000L

    /**
     * 安装或升级 App 后的首次进入补一次检查：随包内置的基线插件不保证是最新版本，
     * 而常规检查受用户选择的间隔节流。用户关闭自动更新时仍然尊重开关。
     */
    fun shouldCheckOnFirstLaunch(
        intervalHours: Int = intervalHours(),
        currentVersionCode: Int,
        seenVersionCode: Int,
    ): Boolean = intervalHours != INTERVAL_OFF && currentVersionCode != seenVersionCode

    fun seenVersionCode(): Int = PrefsBridge.getInt(
        RootConstants.KEY_PROVIDER_AUTO_UPDATE_LAST_SEEN_VERSION_CODE,
        0,
    )

    fun recordSeenVersionCode(versionCode: Int) {
        PrefsBridge.putInt(
            RootConstants.KEY_PROVIDER_AUTO_UPDATE_LAST_SEEN_VERSION_CODE,
            versionCode,
        )
    }

    fun recordCheck(nowMs: Long = System.currentTimeMillis()) {
        PrefsBridge.putLong(RootConstants.KEY_PROVIDER_AUTO_UPDATE_LAST_CHECK, nowMs)
    }
}

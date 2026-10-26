/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider.auto

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.juren233.hyperlyricsenhanced.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * 后台自动更新的调度入口：常驻周期任务 + 打开 App 时的节流补充检查
 * （国产 ROM 对后台任务限制严格，打开 App 的检查才是实际主路径）。
 *
 * 用户选择的间隔只影响打开 App 的节流；周期任务固定 24 小时，并带弹性窗口
 * 与「电量不低」约束，让系统把它并入别的唤醒，而不是单独把设备叫醒。选择
 * 「不开启」时取消全部任务；选择 24 小时时只保留周期任务。
 * 安装/升级 App 后的首次进入不受间隔节流，补一次检查以更新随包基线插件。
 */
object ProviderAutoUpdateScheduler {
    private const val PERIODIC_WORK_NAME = "provider_auto_update_periodic"
    private const val APP_OPEN_WORK_NAME = "provider_auto_update_app_open"

    /** 周期任务的重复间隔与弹性窗口：只在每个周期的最后 [FLEX_HOURS] 小时内择机运行。 */
    private const val PERIOD_HOURS = 24L
    private const val FLEX_HOURS = 6L
    private const val BACKOFF_MINUTES = 30L

    fun onAppOpened(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val intervalHours = ProviderAutoUpdatePolicy.intervalHours()
        val forceCheck = ProviderAutoUpdatePolicy.shouldCheckOnFirstLaunch(
            intervalHours = intervalHours,
            currentVersionCode = BuildConfig.VERSION_CODE,
            seenVersionCode = ProviderAutoUpdatePolicy.seenVersionCode(),
        )

        if (intervalHours == ProviderAutoUpdatePolicy.INTERVAL_OFF) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(APP_OPEN_WORK_NAME)
            return
        }

        // 打开 App 的检查发生在用户使用设备时，只要求联网；后台周期另外要求电量不低。
        val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodicConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val periodic = PeriodicWorkRequestBuilder<ProviderAutoUpdateWorker>(
            PERIOD_HOURS,
            TimeUnit.HOURS,
            FLEX_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(periodicConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        // UPDATE：已注册的旧周期任务（无弹性窗口与电量约束）在升级后也会换成新规格，
        // 按 WorkManager 的 UPDATE 语义沿用已有排期，不会因每次打开 App 而重新计时。
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )

        if (intervalHours == ProviderAutoUpdatePolicy.INTERVAL_PERIODIC_ONLY && !forceCheck) {
            // 完全交给后台周期，打开 App 不额外触发。
            return
        }
        if (forceCheck || ProviderAutoUpdatePolicy.shouldCheckOnAppOpen(intervalHours)) {
            val oneTime = OneTimeWorkRequestBuilder<ProviderAutoUpdateWorker>()
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(
                APP_OPEN_WORK_NAME,
                // 首次补检查用 KEEP：已有检查在跑时不要因再次进入 App 而取消重来；
                // 常规节流检查到期时用 REPLACE 保证执行一轮新的检查。
                if (forceCheck) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
                oneTime,
            )
        }
    }
}

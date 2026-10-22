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
import java.util.concurrent.TimeUnit

/**
 * 后台自动更新的调度入口：常驻周期任务 + 打开 App 时的节流补充检查
 * （国产 ROM 对后台任务限制严格，打开 App 的检查才是实际主路径）。
 */
object ProviderAutoUpdateScheduler {
    private const val PERIODIC_WORK_NAME = "provider_auto_update_periodic"
    private const val APP_OPEN_WORK_NAME = "provider_auto_update_app_open"

    /** 周期任务的执行间隔。 */
    private const val PERIOD_INTERVAL_HOURS = 24L

    fun onAppOpened(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodic = PeriodicWorkRequestBuilder<ProviderAutoUpdateWorker>(
            PERIOD_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )

        if (ProviderAutoUpdatePolicy.isEnabled() && ProviderAutoUpdatePolicy.shouldCheckOnAppOpen()) {
            val oneTime = OneTimeWorkRequestBuilder<ProviderAutoUpdateWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(
                APP_OPEN_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                oneTime,
            )
        }
    }
}

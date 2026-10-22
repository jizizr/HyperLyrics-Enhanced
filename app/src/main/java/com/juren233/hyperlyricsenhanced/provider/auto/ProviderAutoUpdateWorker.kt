/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider.auto

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderAcquisitionLog
import com.juren233.hyperlyricsenhanced.root.RootApplication
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 周期后台自动更新插件。Worker 可能把 App 进程从冷启动拉起，此时
 * Xposed service 尚未通过 [RootApplication] 异步绑定完成；不等绑定就写文件
 * 会退回私有目录并造成「界面已更新、目标进程 remote_file_missing」，
 * 因此绑定超时一律返回 retry。
 */
class ProviderAutoUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!ProviderAutoUpdatePolicy.isEnabled()) {
            return Result.success()
        }
        // 模块未激活/无 root 时 service 永远不会绑定，一直指数退避只是空转：
        // 达到尝试上限后放弃本次，等下一个周期或下次打开 App 再触发。
        if (runAttemptCount >= MAX_RUN_ATTEMPTS) {
            OfficialProviderAcquisitionLog.warn(
                "插件自动更新重试达到上限，放弃本轮: attempts=$runAttemptCount",
            )
            return Result.failure()
        }
        val service = awaitXposedService()
        if (service == null) {
            OfficialProviderAcquisitionLog.warn(
                "插件自动更新等待 Xposed 服务超时，稍后重试",
            )
            return Result.retry()
        }
        return try {
            val outcome = OfficialProviderAutoUpdater.updateInstalledPlugins(applicationContext)
            OfficialProviderAcquisitionLog.info(
                "插件自动更新检查完成: checked=${outcome.checked} " +
                    "updated=${outcome.updated.joinToString()} " +
                    "failed=${outcome.failed.joinToString()}",
            )
            // 目录拉取成功即视为完成了一次检查（含部分插件失败的情况）；
            // 抛异常的失败不记录，保证下次打开 App 仍会触发检查。
            ProviderAutoUpdatePolicy.recordCheck()
            if (outcome.failed.isEmpty()) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OfficialProviderAcquisitionLog.warn(
                "插件自动更新检查失败，稍后重试: " +
                    "error=${OfficialProviderAcquisitionLog.describe(e)}",
            )
            Result.retry()
        }
    }

    private suspend fun awaitXposedService(
        timeoutMs: Long = SERVICE_BIND_TIMEOUT_MS,
    ): XposedService? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (RootApplication.xposedService == null) {
            if (SystemClock.elapsedRealtime() >= deadline) return null
            delay(SERVICE_POLL_INTERVAL_MS)
        }
        return RootApplication.xposedService
    }

    private companion object {
        const val SERVICE_BIND_TIMEOUT_MS = 15_000L
        const val SERVICE_POLL_INTERVAL_MS = 250L

        /** 单次调度内的最大尝试次数（指数退避约 30 分钟起步）。 */
        const val MAX_RUN_ATTEMPTS = 6
    }
}

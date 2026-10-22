/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider.auto

import android.content.Context
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderAcquisitionLog
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderRepository
import com.juren233.hyperlyricsenhanced.provider.ProviderAutoUpdateDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后台自动更新已安装且已启用的插件（HLE 插件化计划 1.3）。
 *
 * 只在 Xposed service 已绑定的前提下工作：安装一律走 Remote File 并写后校验，
 * service 未就绪由 Worker 重试，不允许退回应用私有目录。
 */
object OfficialProviderAutoUpdater {

    data class Outcome(
        val checked: Int,
        val updated: List<String>,
        val failed: List<String>,
    )

    suspend fun updateInstalledPlugins(context: Context): Outcome = withContext(Dispatchers.IO) {
        // includeTargetState=false：后台只读界面流程持久化的目标状态缓存，
        // 不触发 root 读回（未授权时会反复弹授权）；缓存为空时坏版本降级
        // 自然退化为不降级。
        val items = OfficialProviderRepository.loadItems(context, includeTargetState = false)
        val updatable = items.filter { it.enabled && it.installed && !it.needsRepair }
        val updated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        updatable.forEach { item ->
            val release = item.selectedRelease
            if (release == null) return@forEach
            if (!ProviderAutoUpdateDecision.shouldReplace(
                    installedVersionCode = item.installedVersionCode,
                    candidateVersionCode = release.versionCode,
                    badVersionCodes = item.badVersionCodes,
                )
            ) {
                return@forEach
            }
            try {
                OfficialProviderRepository.downloadAndInstall(
                    context = context,
                    item = item,
                    requireRemoteFile = true,
                )
                updated += item.catalog.id
                OfficialProviderAcquisitionLog.info(
                    "插件后台自动更新完成: id=${item.catalog.id} " +
                        "${item.installedVersionCode} -> ${release.versionCode}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += item.catalog.id
                OfficialProviderAcquisitionLog.warn(
                    "插件后台自动更新失败: id=${item.catalog.id} " +
                        "${item.installedVersionCode} -> ${release.versionCode} " +
                        "error=${OfficialProviderAcquisitionLog.describe(e)}",
                )
            }
        }
        Outcome(checked = updatable.size, updated = updated, failed = failed)
    }
}

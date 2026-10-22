/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.juren233.hyperlyricsenhanced.common.UIConstants
import dalvik.system.InMemoryDexClassLoader
import io.github.libxposed.api.XposedModule
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

object OfficialProviderRuntime {
    private const val TAG = "OfficialProviderRuntime"

    /** 插件加载成功后经过该时长仍未退出，视为一次健康运行并清除崩溃计数标记。 */
    private const val HEALTHY_GRACE_MS = 30_000L

    private val loadedPackages = ConcurrentHashMap.newKeySet<String>()
    private val loggedUnavailableReasons = ConcurrentHashMap.newKeySet<String>()

    fun installIfAvailable(
        module: XposedModule,
        targetClassLoader: ClassLoader,
        packageName: String,
        processName: String,
    ): Boolean {
        val definition = OfficialProviderCatalog.definitionForPackage(packageName) ?: return false
        if (packageName == OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME) return false
        if (definition.systemMediaRuntime) return false
        if (packageName in loadedPackages) return true

        return try {
            // 远程偏好在被 Hook 进程里是只读的（libxposed API 契约），
            // 只用于读取模块下发的开关与安装记录。
            val preferences = module.getRemotePreferences(UIConstants.PREF_NAME)
            val enabled = preferences.getBoolean(
                OfficialProviderCatalog.enabledKey(definition.id),
                false,
            )
            if (!enabled) {
                return logUnavailable(module, definition.id, packageName, "disabled")
            }
            val installedVersion = preferences.getInt(
                OfficialProviderCatalog.installedVersionKey(definition.id),
                0,
            )
            if (installedVersion <= 0) {
                return logUnavailable(module, definition.id, packageName, "installed_version_missing")
            }
            val remoteName = preferences.getString(
                OfficialProviderCatalog.activeFileKey(packageName),
                null,
            )?.takeIf(String::isNotBlank)
                ?: return logUnavailable(module, definition.id, packageName, "active_file_missing")

            // 崩溃计数、坏版本、实际加载版本写入目标 App 自己的 filesDir；
            // 目录不可用时退化为无标记模式继续加载，绝不阻断插件本身。
            val store = ProviderTargetStateStore.forTargetProcess(packageName)

            // 崩溃回退只在主进程计数：secondary process 生命周期天然更短，快进快出
            // 会把正常退出误算成崩溃。
            val isMainProcess = processName == packageName
            val decision = if (isMainProcess) {
                ProviderLoadMarker.decide(
                    pendingVersionCode = installedVersion,
                    marker = store?.readMarker(definition.id),
                    previousVersionCode = preferences.getInt(
                        OfficialProviderCatalog.previousVersionKey(definition.id),
                        0,
                    ),
                )
            } else {
                ProviderLoadMarker.Decision.LoadNormally(
                    versionCode = installedVersion,
                    newMarker = ProviderLoadMarker.MarkerState(
                        installedVersion,
                        1,
                        System.currentTimeMillis(),
                    ),
                )
            }

            when (decision) {
                is ProviderLoadMarker.Decision.Rollback -> {
                    store?.addBadVersion(definition.id, decision.badVersionCode)
                    val fallbackVersion = decision.fallbackVersionCode
                    if (fallbackVersion == null) {
                        return logUnavailable(
                            module, definition.id, packageName, "rollback_without_previous",
                        )
                    }
                    val fallbackName = OfficialProviderCatalog.remoteFileName(
                        definition.id,
                        fallbackVersion,
                    )
                    if (fallbackName !in module.listRemoteFiles()) {
                        return logUnavailable(
                            module, definition.id, packageName, "rollback_file_missing:$fallbackName",
                        )
                    }
                    module.log(
                        Log.WARN,
                        TAG,
                        "插件连续异常启动，回退上一版本: id=${definition.id} " +
                            "bad=${decision.badVersionCode} fallback=$fallbackVersion",
                    )
                    loadPlugin(
                        module = module,
                        targetClassLoader = targetClassLoader,
                        definition = definition,
                        packageName = packageName,
                        processName = processName,
                        remoteName = fallbackName,
                        expectedVersion = fallbackVersion,
                        store = store,
                        scheduleMarkerClear = false,
                    )
                }

                is ProviderLoadMarker.Decision.LoadNormally -> {
                    if (remoteName !in module.listRemoteFiles()) {
                        return logUnavailable(
                            module,
                            definition.id,
                            packageName,
                            "remote_file_missing:$remoteName",
                        )
                    }
                    if (isMainProcess) {
                        store?.writeMarker(definition.id, decision.newMarker)
                    }
                    loadPlugin(
                        module = module,
                        targetClassLoader = targetClassLoader,
                        definition = definition,
                        packageName = packageName,
                        processName = processName,
                        remoteName = remoteName,
                        expectedVersion = installedVersion,
                        store = store,
                        scheduleMarkerClear = isMainProcess,
                    )
                }
            }
        } catch (error: Throwable) {
            module.log(
                Log.ERROR,
                TAG,
                "官方 Provider 加载失败: id=${definition.id} package=$packageName",
                error,
            )
            false
        }
    }

    private fun loadPlugin(
        module: XposedModule,
        targetClassLoader: ClassLoader,
        definition: OfficialProviderCatalog.Definition,
        packageName: String,
        processName: String,
        remoteName: String,
        expectedVersion: Int,
        store: ProviderTargetStateStore?,
        scheduleMarkerClear: Boolean,
    ): Boolean {
        module.log(
            Log.INFO,
            TAG,
            "开始加载官方 Provider: id=${definition.id} package=$packageName " +
                "version=$expectedVersion file=$remoteName",
        )
        val packBytes = ParcelFileDescriptor.AutoCloseInputStream(
            module.openRemoteFile(remoteName),
        ).use { it.readBytes() }
        val verified = ProviderPackVerifier.verify(
            packBytes = packBytes,
            expectedTargetPackage = packageName,
        )
        require(verified.manifest.pluginId == definition.id) {
            "Provider Pack ID 与目录不一致: expected=${definition.id}, " +
                "actual=${verified.manifest.pluginId}"
        }
        require(verified.manifest.versionCode == expectedVersion) {
            "Provider Pack 版本与已安装记录不一致: expected=$expectedVersion, " +
                "actual=${verified.manifest.versionCode}"
        }
        val pluginLoader = InMemoryDexClassLoader(
            ByteBuffer.wrap(verified.classesDex),
            OfficialProviderRuntime::class.java.classLoader,
        )
        val entryClass = pluginLoader.loadClass(verified.manifest.entryClass)
        val plugin = runCatching {
            entryClass.getField("INSTANCE").get(null)
        }.getOrElse {
            entryClass.getDeclaredConstructor().newInstance()
        }
        require(plugin is OfficialProviderPlugin) {
            "Provider 入口未实现官方插件 API"
        }
        val host = OfficialProviderHookHost(
            module = module,
            targetClassLoader = targetClassLoader,
            packageName = packageName,
            processName = processName,
        )
        plugin.install(host)
        host.logInstalled(definition.id)
        loadedPackages += packageName
        store?.writeLoadedVersion(definition.id, expectedVersion)
        if (scheduleMarkerClear) {
            scheduleMarkerClear(store, definition.id)
        }
        module.log(
            Log.INFO,
            TAG,
            "官方 Provider 已加载: id=${definition.id} package=$packageName version=$expectedVersion",
        )
        return true
    }

    /** 健康运行 [HEALTHY_GRACE_MS] 后清除崩溃计数标记；期间进程死亡则标记留存。 */
    private fun scheduleMarkerClear(store: ProviderTargetStateStore?, pluginId: String) {
        if (store == null) return
        Handler(Looper.getMainLooper()).postDelayed({
            store.clearMarker(pluginId)
        }, HEALTHY_GRACE_MS)
    }

    private fun logUnavailable(
        module: XposedModule,
        pluginId: String,
        packageName: String,
        reason: String,
    ): Boolean {
        if (loggedUnavailableReasons.add("$packageName:$reason")) {
            module.log(
                Log.INFO,
                TAG,
                "官方 Provider 未加载: id=$pluginId package=$packageName reason=$reason",
            )
        }
        return false
    }
}

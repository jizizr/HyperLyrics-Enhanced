/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.root.utils.ShellUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * App 侧读取目标进程写入其 filesDir 的插件健康状态（坏版本、实际加载版本）。
 *
 * libxposed 在被 Hook 进程里只提供只读的远程偏好/远程文件，目标进程的
 * 状态文件（[ProviderTargetStateStore]）只能通过 root 通道读回。
 *
 * root 读回只扫描已安装插件的目标包，且只由用户在场的界面流程触发
 * （[readFresh]）；读回成功后快照持久化到本地偏好。后台自动更新使用
 * [persisted] 直接读缓存，永不调用 su——未授予 root 时不会反复弹授权，
 * 有 root 的用户打开过 App 后后台仍能拿到坏版本做降级脱困。
 */
object OfficialProviderHealthStore {

    private const val CACHE_TTL_MS = 30_000L

    private val refreshLock = Mutex()

    @Volatile
    private var cachedAtMs: Long = 0L

    @Volatile
    private var cached: ProviderTargetStateSnapshot = ProviderTargetStateSnapshot.EMPTY

    /** 界面流程：按需 root 读回并刷新持久化缓存；读回失败时回退上次缓存。 */
    suspend fun readFresh(force: Boolean = false): ProviderTargetStateSnapshot {
        val targetPackages = installedTargetPackages()
        if (targetPackages.isEmpty()) {
            // 一个插件都没装：没有任何状态可读，不起 su。
            val empty = ProviderTargetStateSnapshot.EMPTY
            cached = empty
            cachedAtMs = System.currentTimeMillis()
            persistSnapshot(empty)
            return empty
        }
        val now = System.currentTimeMillis()
        if (!force && now - cachedAtMs < CACHE_TTL_MS) return cached
        return refreshLock.withLock {
            val lockedNow = System.currentTimeMillis()
            if (!force && lockedNow - cachedAtMs < CACHE_TTL_MS) return@withLock cached
            val readLines = ShellUtils.execRootScriptLines("sh", buildReadScript(targetPackages))
            val parsed = if (readLines != null) {
                // 只有 root 读回真正成功才覆盖缓存，失败（无 root/被拒）保留旧值。
                ProviderTargetStateParser.parse(readLines).also(::persistSnapshot)
            } else {
                persisted()
            }
            cached = parsed
            cachedAtMs = System.currentTimeMillis()
            parsed
        }
    }

    /** 后台流程：只读持久化缓存，不触发任何 root 请求。 */
    fun persisted(): ProviderTargetStateSnapshot =
        runCatching {
            PrefsBridge.getString(RootConstants.KEY_PROVIDER_TARGET_STATE_CACHE, null)
                ?.let { snapshotJson.decodeFromString<ProviderTargetStateSnapshot>(it) }
        }.getOrNull() ?: ProviderTargetStateSnapshot.EMPTY

    private fun persistSnapshot(snapshot: ProviderTargetStateSnapshot) {
        runCatching {
            PrefsBridge.putString(
                RootConstants.KEY_PROVIDER_TARGET_STATE_CACHE,
                snapshotJson.encodeToString(
                    ProviderTargetStateSnapshot.serializer(),
                    snapshot,
                ),
            )
        }
    }

    /**
     * 有 root 时清除目标 App filesDir 里该插件的崩溃标记（.marker）：
     * 「立即生效」强停成功后调用——强停发生在健康宽限期内会被目标进程
     * 算作一次崩溃计数。尽力而为，无 root 时静默跳过（靠标记衰减兜底）。
     */
    suspend fun clearLoadMarkers(pluginId: String, targetPackages: Collection<String>) =
        clearPluginFiles(pluginId, targetPackages, setOf(MARKER_SUFFIX))

    /**
     * 手动安装（界面触发）成功后调用：清崩溃标记与坏版本记录——用户主动
     * 选择（重）装这个版本就是给它一次新机会，不应继承旧的误判。
     * 不清 .loaded，装完更新还要靠它显示「重启后生效」。
     */
    suspend fun clearBadVersionMarks(pluginId: String, targetPackages: Collection<String>) =
        clearPluginFiles(pluginId, targetPackages, setOf(MARKER_SUFFIX, BAD_SUFFIX))

    /** 删除插件时调用：清掉该插件全部目标进程状态（标记/坏版本/已加载版本）。 */
    suspend fun clearAllPluginState(pluginId: String, targetPackages: Collection<String>) =
        clearPluginFiles(
            pluginId,
            targetPackages,
            setOf(MARKER_SUFFIX, BAD_SUFFIX, LOADED_SUFFIX),
        )

    private suspend fun clearPluginFiles(
        pluginId: String,
        targetPackages: Collection<String>,
        fileSuffixes: Set<String>,
    ) {
        if (targetPackages.isEmpty()) return
        ShellUtils.execRootScriptSilent("sh", buildClearScript(pluginId, targetPackages, fileSuffixes))
    }

    /** 已安装插件的目标包集合：读回脚本只扫这些目录。 */
    private fun installedTargetPackages(): List<String> =
        OfficialProviderCatalog.definitions
            .filter { definition ->
                PrefsBridge.getInt(OfficialProviderCatalog.installedVersionKey(definition.id), 0) > 0
            }
            .flatMap { it.targetPackages }
            .distinct()

    internal fun buildReadScript(targetPackages: List<String>): String = buildString {
        targetPackages.forEach { rawPackage ->
            // 目标包名来自内置目录（点分字母数字），剔除引号后可直接拼进路径。
            val pkg = rawPackage.replace("'", "").replace(" ", "")
            append("for f in /data/user/*/")
            append(pkg)
            append("/files/")
            append(ProviderTargetStateStore.DIRECTORY_NAME)
            append("/*; do [ -f \"\$f\" ] || continue; echo \"=== \$f\"; cat \"\$f\" 2>/dev/null; done\n")
        }
        append("exit 0")
    }

    internal fun buildClearScript(
        pluginId: String,
        targetPackages: Collection<String>,
        fileSuffixes: Set<String>,
    ): String = buildString {
        targetPackages.forEach { rawPackage ->
            val pkg = rawPackage.replace("'", "").replace(" ", "")
            fileSuffixes.forEach { suffix ->
                append("rm -f /data/user/*/")
                append(pkg)
                append("/files/")
                append(ProviderTargetStateStore.DIRECTORY_NAME)
                append("/")
                append(pluginId)
                append('.')
                append(suffix)
                append(" 2>/dev/null\n")
            }
        }
        append("exit 0")
    }

    private const val MARKER_SUFFIX = "marker"
    private const val BAD_SUFFIX = "bad"
    private const val LOADED_SUFFIX = "loaded"

    private val snapshotJson = Json { ignoreUnknownKeys = true }
}

@Serializable
data class ProviderTargetStateSnapshot(
    val badVersionsByPlugin: Map<String, Set<Int>> = emptyMap(),
    val loadedVersionByPlugin: Map<String, Int> = emptyMap(),
) {
    companion object {
        val EMPTY = ProviderTargetStateSnapshot()
    }
}

/**
 * 插件条目的「生效状态」徽标：正常 / 待重启生效 / 已回退。
 * 已安装版本本身在坏版本集合里时，实际运行的是回退后的上一版，
 * 不再提示「重启后生效」（重启也不会改变什么），改提示已回退。
 */
object ProviderApplyState {

    enum class Badge { NONE, RESTART_PENDING, ROLLED_BACK }

    fun badge(
        installedVersionCode: Int,
        loadedVersionCode: Int,
        badVersionCodes: Set<Int>,
    ): Badge = when {
        installedVersionCode <= 0 -> Badge.NONE
        installedVersionCode in badVersionCodes -> Badge.ROLLED_BACK
        loadedVersionCode > 0 && loadedVersionCode < installedVersionCode -> Badge.RESTART_PENDING
        else -> Badge.NONE
    }
}

/** root 读回输出的解析（`=== 路径` 后跟文件内容行）；纯函数便于单测。 */
object ProviderTargetStateParser {
    private val STATE_FILE_PATTERN =
        Regex("""^.*/${ProviderTargetStateStore.DIRECTORY_NAME}/([a-z0-9-]+)\.(bad|loaded|marker)$""")

    fun parse(lines: List<String>): ProviderTargetStateSnapshot {
        val badVersions = mutableMapOf<String, MutableSet<Int>>()
        val loadedVersions = mutableMapOf<String, Int>()
        var currentPluginId: String? = null
        var currentKind: String? = null
        for (line in lines) {
            if (line.startsWith("=== ")) {
                val match = STATE_FILE_PATTERN.matchEntire(line.removePrefix("=== ").trim())
                val pluginId = match?.groupValues?.get(1)
                currentPluginId = pluginId?.takeIf { OfficialProviderCatalog.definitionForId(it) != null }
                currentKind = match?.groupValues?.get(2)
                continue
            }
            val pluginId = currentPluginId ?: continue
            when (currentKind) {
                "bad" -> line.toIntOrNull()?.let { version ->
                    badVersions.getOrPut(pluginId) { mutableSetOf() }.add(version)
                }
                "loaded" -> line.trim().toIntOrNull()?.let { version ->
                    loadedVersions[pluginId] = maxOf(loadedVersions[pluginId] ?: 0, version)
                }
                // marker 只在目标进程内部使用，App 侧不消费。
            }
        }
        return ProviderTargetStateSnapshot(
            badVersionsByPlugin = badVersions,
            loadedVersionByPlugin = loadedVersions,
        )
    }
}

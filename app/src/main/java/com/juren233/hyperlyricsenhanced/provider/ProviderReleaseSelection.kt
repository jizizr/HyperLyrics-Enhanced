/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import kotlinx.serialization.Serializable

/**
 * 目录 releases 字段里单个发行版的描述。旧目录没有该字段时，顶层字段按
 * 「无约束的历史版本」解读（见 [asTopLevelRelease]）。
 */
@Serializable
data class ProviderCatalogRelease(
    val versionCode: Int,
    val versionName: String?,
    val assetUrl: String,
    val sha256: String,
    val pluginApiVersion: Int = OfficialProviderCatalog.PLUGIN_API_VERSION,
    val minCoreVersionCode: Int = 0,
)

/**
 * 按当前主程序条件挑选「可安装的最新插件版本」。
 *
 * 规则（HLE 插件化计划 1.1/1.2）：
 * - pluginApiVersion 必须在主程序支持范围内；
 * - minCoreVersionCode 不得高于当前主程序版本；
 * - 版本号不得出现在目录 revokedVersionCodes 或本机坏版本集合里；
 * - 目录尚未升级到 releases 格式时回退顶层字段。
 */
object ProviderReleaseSelection {

    fun asTopLevelRelease(entry: ProviderCatalogEntry): ProviderCatalogRelease? {
        if (!entry.available) return null
        val versionCode = entry.versionCode ?: return null
        val assetUrl = entry.assetUrl ?: return null
        val sha256 = entry.sha256 ?: return null
        return ProviderCatalogRelease(
            versionCode = versionCode,
            versionName = entry.versionName,
            assetUrl = assetUrl,
            sha256 = sha256,
        )
    }

    /** 旧目录（无 releases）等价于只有一个顶层发行版。 */
    fun allReleases(entry: ProviderCatalogEntry): List<ProviderCatalogRelease> =
        entry.releases.ifEmpty { listOfNotNull(asTopLevelRelease(entry)) }

    fun isCompatible(
        release: ProviderCatalogRelease,
        coreVersionCode: Int,
        pluginApiRange: IntRange = OfficialProviderCatalog.PLUGIN_API_SUPPORTED_RANGE,
    ): Boolean = release.pluginApiVersion in pluginApiRange &&
        release.minCoreVersionCode <= coreVersionCode

    fun isExcluded(
        release: ProviderCatalogRelease,
        revokedVersionCodes: Set<Int> = emptySet(),
        badVersionCodes: Set<Int> = emptySet(),
    ): Boolean = release.versionCode in revokedVersionCodes || release.versionCode in badVersionCodes

    /**
     * 当前主程序能用的最新发行版；没有则返回 null（未发布或全部不兼容）。
     */
    fun selectLatestCompatible(
        entry: ProviderCatalogEntry,
        coreVersionCode: Int,
        pluginApiRange: IntRange = OfficialProviderCatalog.PLUGIN_API_SUPPORTED_RANGE,
        revokedVersionCodes: Set<Int> = entry.revokedVersionCodes.toSet(),
        badVersionCodes: Set<Int> = emptySet(),
    ): ProviderCatalogRelease? =
        allReleases(entry)
            .filter { !isExcluded(it, revokedVersionCodes, badVersionCodes) }
            .filter { isCompatible(it, coreVersionCode, pluginApiRange) }
            .maxByOrNull { it.versionCode }

    /**
     * 存在比已装版本新的发行版，但所有这类发行版都要求更高的主程序版本
     * （或使用了当前主程序不支持的插件 API）。此时插件页只提示更新 App，
     * 不提供安装入口。
     */
    fun coreUpdateRequired(
        entry: ProviderCatalogEntry,
        installedVersionCode: Int,
        coreVersionCode: Int,
        pluginApiRange: IntRange = OfficialProviderCatalog.PLUGIN_API_SUPPORTED_RANGE,
        revokedVersionCodes: Set<Int> = entry.revokedVersionCodes.toSet(),
        badVersionCodes: Set<Int> = emptySet(),
    ): Boolean {
        val newer = allReleases(entry)
            .filter { it.versionCode > installedVersionCode }
            .filter { !isExcluded(it, revokedVersionCodes, badVersionCodes) }
        if (newer.isEmpty()) return false
        return newer.none { isCompatible(it, coreVersionCode, pluginApiRange) }
    }
}

/**
 * 后台自动更新的安装决策：候选比已装新则升级；仅在已装版本被本机标记为
 * 坏版本时才允许降级到兼容候选（用于自动脱离坏版本）。
 */
object ProviderAutoUpdateDecision {

    fun shouldReplace(
        installedVersionCode: Int,
        candidateVersionCode: Int,
        badVersionCodes: Set<Int>,
    ): Boolean {
        if (candidateVersionCode == installedVersionCode) return false
        if (candidateVersionCode > installedVersionCode) return true
        return installedVersionCode in badVersionCodes
    }
}

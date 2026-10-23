/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 按「包文件名 + 整包摘要」缓存的验签结果。
 *
 * 记录只由主程序在安装/读取时写入远端偏好；目标进程只读取，并在整包摘要
 * 与记录一致时跳过逐 DEX 的 SHA-256 与 Ed25519 验签。
 */
@Serializable
internal data class CachedProviderPack(
    val packSha256: String,
    val manifest: ProviderPackManifest,
)

internal object ProviderPackVerificationCache {
    private const val MAX_CACHE_CHARS = 256 * 1024
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun lookup(
        cacheJson: String?,
        remoteName: String,
        packSha256: String,
    ): ProviderPackManifest? {
        if (cacheJson.isNullOrBlank() || packSha256.isBlank()) return null
        val record = decode(cacheJson)[remoteName] ?: return null
        if (!record.packSha256.equals(packSha256, ignoreCase = true)) return null
        return record.manifest
    }

    fun record(
        cacheJson: String?,
        remoteName: String,
        packSha256: String,
        manifest: ProviderPackManifest,
    ): String {
        val records = decode(cacheJson).toMutableMap()
        records[remoteName] = CachedProviderPack(
            packSha256 = packSha256,
            manifest = manifest,
        )
        return encode(records)
    }

    fun retain(cacheJson: String?, remoteNames: Set<String>): String? {
        val records = decode(cacheJson)
        val retained = records.filterKeys { it in remoteNames }
        if (retained.size == records.size) return cacheJson
        return encode(retained)
    }

    internal fun decode(cacheJson: String?): Map<String, CachedProviderPack> {
        if (cacheJson.isNullOrBlank() || cacheJson.length > MAX_CACHE_CHARS) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, CachedProviderPack>>(cacheJson)
        }.getOrDefault(emptyMap())
    }

    internal fun encode(records: Map<String, CachedProviderPack>): String =
        json.encodeToString<Map<String, CachedProviderPack>>(records.toSortedMap())
}

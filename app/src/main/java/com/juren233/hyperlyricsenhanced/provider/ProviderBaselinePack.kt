/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.content.Context
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.libxposed.api.XposedModule
import java.util.zip.ZipFile

/**
 * Baseline Provider Pack shipped inside the module APK under `assets/providers/`.
 *
 * It keeps a Provider working when no remote Pack is installed yet. The Pack is fully
 * verified (catalog signature, SHA-256, Ed25519 and manifest compatibility) by the
 * `prepareAppleMusicBaselinePack` build task; at runtime it only gets the structural and
 * manifest checks of [ProviderPackVerifier.loadBundled], because it lives in the same APK as
 * the code that would verify it. The asset is stored uncompressed (`noCompress "hlp"`).
 */
internal object ProviderBaselinePack {
    private const val ASSET_PREFIX = "assets/providers/"

    @Volatile
    private var cached: Pair<String, VerifiedProviderPack?>? = null

    fun assetEntry(pluginId: String): String = "$ASSET_PREFIX$pluginId.hlp"

    fun verified(context: Context, pluginId: String): VerifiedProviderPack? =
        verified(context.applicationInfo.sourceDir, pluginId)

    fun verified(module: XposedModule, pluginId: String): VerifiedProviderPack? =
        runCatching { verified(module.getModuleApplicationInfo().sourceDir, pluginId) }
            .getOrNull()

    fun verified(apkPath: String, pluginId: String): VerifiedProviderPack? {
        val key = "$apkPath|$pluginId|${BuildConfig.VERSION_CODE}"
        cached?.let { (cachedKey, value) -> if (cachedKey == key) return value }
        return synchronized(this) {
            cached?.let { (cachedKey, value) -> if (cachedKey == key) return value }
            val result = runCatching {
                readBytes(apkPath, pluginId)?.let { packBytes ->
                    ProviderPackVerifier.loadBundled(packBytes = packBytes)
                }
            }.getOrNull()
            cached = key to result
            result
        }
    }

    fun manifest(context: Context, pluginId: String): ProviderPackManifest? =
        verified(context, pluginId)?.manifest

    /**
     * Only the baseline manifest, without reading its DEX. Target processes use it to
     * decide between an installed remote Pack and a newer baseline before loading either.
     */
    fun manifest(module: XposedModule, pluginId: String): ProviderPackManifest? =
        runCatching { module.getModuleApplicationInfo().sourceDir }.getOrNull()
            ?.let { apkPath -> cachedManifest(apkPath, pluginId) ?: readManifest(apkPath, pluginId) }

    private fun cachedManifest(apkPath: String, pluginId: String): ProviderPackManifest? {
        val key = "$apkPath|$pluginId|${BuildConfig.VERSION_CODE}"
        return cached?.takeIf { it.first == key }?.second?.manifest
    }

    private fun readManifest(apkPath: String, pluginId: String): ProviderPackManifest? =
        runCatching {
            ZipFile(apkPath).use { apk ->
                val entry = apk.getEntry(assetEntry(pluginId)) ?: return@use null
                apk.getInputStream(entry).use(ProviderPackVerifier::readManifest)
            }
        }.getOrNull()

    private fun readBytes(apkPath: String, pluginId: String): ByteArray? = runCatching {
        ZipFile(apkPath).use { apk ->
            val entry = apk.getEntry(assetEntry(pluginId)) ?: return@use null
            apk.getInputStream(entry).use { it.readBytes() }
        }
    }.getOrNull()
}

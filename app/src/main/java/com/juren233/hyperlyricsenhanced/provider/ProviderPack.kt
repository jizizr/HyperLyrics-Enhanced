/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.util.Log
import com.juren233.hyperlyricsenhanced.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.spec.InvalidKeySpecException
import java.util.Base64
import java.util.zip.ZipInputStream

@Serializable
data class ProviderPackManifest(
    val schemaVersion: Int,
    val pluginApiVersion: Int,
    val pluginId: String,
    val displayName: String,
    val versionName: String,
    val versionCode: Int,
    val minCoreVersionCode: Int,
    val entryClass: String,
    val providerPackageName: String,
    val targetPackages: List<String>,
    val classesSha256: String,
    /**
     * 每个 DEX 条目的摘要，键为 classes.dex / classes2.dex…。
     *
     * 旧包没有该字段，只含 classes.dex；新包由 build_provider_pack.py 写入。
     */
    val dexSha256: Map<String, String> = emptyMap(),
)

data class VerifiedProviderPack(
    val manifest: ProviderPackManifest,
    val dexFiles: List<ByteArray>,
    val packSha256: String,
    val verificationCacheHit: Boolean,
)

object ProviderPackVerifier {
    private const val TAG = "HLEProvider/Verifier"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val DEX_ENTRY = "classes.dex"
    private const val SIGNATURE_ENTRY = "signature.ed25519"
    private const val MAX_PACK_BYTES = 16 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_DEX_BYTES = 12 * 1024 * 1024
    private const val MAX_SIGNATURE_BYTES = 256
    private const val MAX_DEX_FILES = 10
    private const val MAX_TOTAL_DEX_BYTES = 48 * 1024 * 1024
    private const val SIGNATURE_SEPARATOR: Byte = 0
    private val nonDexEntries = setOf(MANIFEST_ENTRY, SIGNATURE_ENTRY)
    private val extraDexEntry = Regex("^classes([2-9][0-9]*)\\.dex$")
    private val json = Json {
        // 新版 Pack 的清单可能新增字段；旧版 App 忽略未知字段而不是拒绝整个 Pack。
        // 真正的兼容边界由 schemaVersion / pluginApiVersion / minCoreVersionCode 承担。
        ignoreUnknownKeys = true
        isLenient = false
    }

    private const val PUBLIC_KEY_DER_BASE64 =
        "MCowBQYDK2VwAyEAUQ05FgAS98xeNOtYppCBq1BUjEhwdxrChjwpK5sRgQU="

    fun verify(
        packBytes: ByteArray,
        expectedTargetPackage: String? = null,
        coreVersionCode: Int = BuildConfig.VERSION_CODE,
        verificationCache: String? = null,
        verificationCacheKey: String? = null,
    ): VerifiedProviderPack {
        require(packBytes.size <= MAX_PACK_BYTES) { "Provider Pack 超过大小限制" }
        val packSha256 = sha256(packBytes)
        val entries = readEntries(packBytes)
        val manifestBytes = requireNotNull(entries[MANIFEST_ENTRY])
        val signatureBytes = requireNotNull(entries[SIGNATURE_ENTRY])
        val dexEntries = orderedDexEntries(entries)
        require(dexEntries.isNotEmpty()) { "Provider Pack 缺少 classes.dex" }
        require(dexEntries.size <= MAX_DEX_FILES) { "Provider Pack DEX 文件过多" }
        require(dexEntries.map { it.index } == (1..dexEntries.size).toList()) {
            "Provider Pack DEX 文件编号不连续"
        }
        val dexFiles = dexEntries.map { it.bytes }

        val cachedManifest = if (verificationCacheKey != null) {
            ProviderPackVerificationCache.lookup(
                cacheJson = verificationCache,
                remoteName = verificationCacheKey,
                packSha256 = packSha256,
            )
        } else {
            null
        }
        val manifest = cachedManifest
            ?: decodeManifest(manifestBytes)
        validateManifest(manifest, expectedTargetPackage, coreVersionCode)

        if (cachedManifest == null) {
            verifyDexDigests(manifest, dexEntries)
            require(
                verifyDetachedSignature(
                    signaturePayload(manifestBytes, dexFiles),
                    signatureBytes,
                )
            ) {
                "Provider Pack 签名无效"
            }
        } else if (BuildConfig.DEBUG) {
            runCatching {
                Log.d(
                    TAG,
                    "Provider Pack 验签缓存命中: key=$verificationCacheKey " +
                        "packSha256=${packSha256.take(12)}",
                )
            }
        }
        return VerifiedProviderPack(
            manifest = manifest,
            dexFiles = dexFiles,
            packSha256 = packSha256,
            verificationCacheHit = cachedManifest != null,
        )
    }

    /**
     * 读取随模块 APK 内置的基线包：保留结构与清单兼容校验，跳过摘要和 Ed25519 验签。
     *
     * 基线包与执行校验的主程序代码同在模块 APK 内，能篡改它的人也能篡改校验本身，
     * 运行时验签不增加防护；签名、摘要与清单已在构建任务 prepareAppleMusicBaselinePack
     * 中完整校验，失败即构建失败。远程下载的包仍必须走 [verify]。
     */
    fun loadBundled(
        packBytes: ByteArray,
        expectedTargetPackage: String? = null,
        coreVersionCode: Int = BuildConfig.VERSION_CODE,
    ): VerifiedProviderPack {
        require(packBytes.size <= MAX_PACK_BYTES) { "Provider Pack 超过大小限制" }
        val entries = readEntries(packBytes)
        val manifestBytes = requireNotNull(entries[MANIFEST_ENTRY])
        val dexEntries = orderedDexEntries(entries)
        require(dexEntries.isNotEmpty()) { "Provider Pack 缺少 classes.dex" }
        require(dexEntries.size <= MAX_DEX_FILES) { "Provider Pack DEX 文件过多" }
        require(dexEntries.map { it.index } == (1..dexEntries.size).toList()) {
            "Provider Pack DEX 文件编号不连续"
        }
        val manifest = decodeManifest(manifestBytes)
        validateManifest(manifest, expectedTargetPackage, coreVersionCode)
        return VerifiedProviderPack(
            manifest = manifest,
            dexFiles = dexEntries.map { it.bytes },
            packSha256 = "",
            verificationCacheHit = false,
        )
    }

    /**
     * 只读出包内 manifest.json，不读取 DEX；用于比较内置基线包与已安装远程包的版本。
     * 打包脚本把 manifest.json 写在第一个条目，正常情况下只需读取几百字节。
     */
    fun readManifest(packStream: java.io.InputStream): ProviderPackManifest? =
        ZipInputStream(packStream).use { zip ->
            generateSequence { zip.nextEntry }
                .firstOrNull { it.name == MANIFEST_ENTRY }
                ?.let { decodeManifest(zip.readEntryBytes(MAX_MANIFEST_BYTES)) }
        }

    private fun decodeManifest(manifestBytes: ByteArray): ProviderPackManifest =
        json.decodeFromString<ProviderPackManifest>(manifestBytes.toString(Charsets.UTF_8))

    private fun validateManifest(
        manifest: ProviderPackManifest,
        expectedTargetPackage: String?,
        coreVersionCode: Int,
    ) {
        require(manifest.schemaVersion == 1) { "不支持的 Provider Pack 格式" }
        require(
            manifest.pluginApiVersion in OfficialProviderCatalog.PLUGIN_API_SUPPORTED_RANGE
        ) {
            "Provider 插件 API 不兼容"
        }
        require(manifest.minCoreVersionCode <= coreVersionCode) {
            "Provider 需要更新版本的 HyperLyrics Enhanced"
        }
        require(manifest.pluginId.matches(Regex("[a-z0-9][a-z0-9-]{1,47}"))) {
            "Provider 插件 ID 无效"
        }
        require(manifest.entryClass.startsWith("com.juren233.hle.providers.")) {
            "Provider 入口类不在允许命名空间"
        }
        require(
            manifest.providerPackageName ==
                OfficialProviderCatalog.OFFICIAL_PROVIDER_PACKAGE_PREFIX + manifest.pluginId
        ) { "Provider 来源标识无效" }

        requireNotNull(
            OfficialProviderCatalog.definitionForId(manifest.pluginId)
        ) { "Provider 不在内置允许列表" }
        // 前向兼容：新版 Pack 可能给既有插件新增目标包名（新音乐 App）。
        // 旧版 App 放行并忽略不认识的包名，只拒绝把其他插件的包名声明进来。
        require(
            manifest.targetPackages.isNotEmpty() && manifest.targetPackages.all {
                OfficialProviderCatalog.isTargetPackageCompatible(manifest.pluginId, it)
            },
        ) { "Provider 目标软件与内置允许列表不一致" }
        expectedTargetPackage?.let { expected ->
            require(expected in manifest.targetPackages) { "Provider 不支持当前音乐软件" }
            require(OfficialProviderCatalog.definitionForPackage(expected)?.id == manifest.pluginId) {
                "Provider 与当前音乐软件不匹配"
            }
        }
    }

    private fun verifyDexDigests(
        manifest: ProviderPackManifest,
        dexEntries: List<DexEntry>,
    ) {
        if (manifest.dexSha256.isEmpty()) {
            require(dexEntries.size == 1) { "Provider Pack 缺少多 DEX 摘要" }
            require(
                sha256(dexEntries.single().bytes).equals(manifest.classesSha256, ignoreCase = true)
            ) {
                "Provider DEX 摘要不匹配"
            }
            return
        }
        require(manifest.dexSha256.keys == dexEntries.map { it.name }.toSet()) {
            "Provider Pack DEX 摘要与文件集合不一致"
        }
        require(
            manifest.dexSha256[DEX_ENTRY].orEmpty()
                .equals(manifest.classesSha256, ignoreCase = true)
        ) {
            "Provider Pack 主 DEX 摘要不一致"
        }
        dexEntries.forEach { entry ->
            require(
                sha256(entry.bytes).equals(
                    manifest.dexSha256[entry.name].orEmpty(),
                    ignoreCase = true,
                )
            ) {
                "Provider DEX 摘要不匹配: ${entry.name}"
            }
        }
    }

    private fun signaturePayload(
        manifestBytes: ByteArray,
        dexFiles: List<ByteArray>,
    ): ByteArray {
        val output = ByteArrayOutputStream(
            manifestBytes.size + dexFiles.sumOf { it.size } + dexFiles.size,
        )
        output.write(manifestBytes)
        dexFiles.forEach { dex ->
            output.write(SIGNATURE_SEPARATOR.toInt())
            output.write(dex)
        }
        return output.toByteArray()
    }

    private fun readEntries(packBytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var totalDexBytes = 0
        ZipInputStream(ByteArrayInputStream(packBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && isAllowedEntry(entry.name)) {
                    "Provider Pack 包含不允许的文件"
                }
                require(result[entry.name] == null) { "Provider Pack 包含重复文件" }
                val limit = when (entry.name) {
                    MANIFEST_ENTRY -> MAX_MANIFEST_BYTES
                    SIGNATURE_ENTRY -> MAX_SIGNATURE_BYTES
                    else -> MAX_DEX_BYTES
                }
                val bytes = zip.readEntryBytes(limit)
                if (dexIndex(entry.name) != null) {
                    totalDexBytes += bytes.size
                    require(totalDexBytes <= MAX_TOTAL_DEX_BYTES) {
                        "Provider Pack DEX 总大小超过限制"
                    }
                }
                result[entry.name] = bytes
                zip.closeEntry()
            }
        }
        return result
    }

    private fun isAllowedEntry(name: String): Boolean =
        name in nonDexEntries || dexIndex(name) != null

    private fun orderedDexEntries(entries: Map<String, ByteArray>): List<DexEntry> =
        entries.mapNotNull { (name, bytes) ->
            dexIndex(name)?.let { index -> DexEntry(index, name, bytes) }
        }.sortedBy { it.index }

    private fun dexIndex(name: String): Int? {
        if (name == DEX_ENTRY) return 1
        val match = extraDexEntry.matchEntire(name) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private data class DexEntry(
        val index: Int,
        val name: String,
        val bytes: ByteArray,
    )

    private fun ZipInputStream.readEntryBytes(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Provider Pack 文件超过大小限制" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    fun verifyDetachedSignature(
        payload: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean {
        val result = runCatching {
            val publicKey = decodeEd25519PublicKey()
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(payload, 0, payload.size)
            verifier.verifySignature(signatureBytes)
        }
        if (BuildConfig.DEBUG && result.isFailure) {
            runCatching {
                Log.e(
                    TAG,
                    "Ed25519 verification failed before result " +
                        "payloadSha256=${sha256(payload)} signatureBytes=${signatureBytes.size}",
                    result.exceptionOrNull(),
                )
            }
        }
        if (BuildConfig.DEBUG && result.isSuccess) {
            runCatching {
                Log.d(
                    TAG,
                    "verify implementation=BouncyCastle-Ed25519Signer result=${result.getOrNull()} " +
                        "payloadSha256=${sha256(payload)} signatureBytes=${signatureBytes.size}",
                )
            }
        }
        return result.getOrDefault(false)
    }

    private fun decodeEd25519PublicKey(): ByteArray {
        val der = Base64.getDecoder().decode(PUBLIC_KEY_DER_BASE64)
        require(der.size == 44) { "Ed25519 公钥 DER 长度无效" }
        val prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03,
            0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        require(der.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            InvalidKeySpecException("Ed25519 公钥不是预期的 X.509 SubjectPublicKeyInfo")
        }
        return der.copyOfRange(prefix.size, der.size)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

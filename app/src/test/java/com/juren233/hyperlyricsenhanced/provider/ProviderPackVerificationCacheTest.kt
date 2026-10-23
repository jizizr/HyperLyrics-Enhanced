/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderPackVerificationCacheTest {

    @Test
    fun lookupRequiresMatchingPackDigest() {
        val encoded = ProviderPackVerificationCache.record(
            cacheJson = null,
            remoteName = "hle-provider-kuwo-2.hlp",
            packSha256 = "a".repeat(64),
            manifest = manifest(versionCode = 2),
        )

        assertEquals(
            2,
            ProviderPackVerificationCache.lookup(
                cacheJson = encoded,
                remoteName = "hle-provider-kuwo-2.hlp",
                packSha256 = "a".repeat(64),
            )?.versionCode,
        )
        assertNull(
            ProviderPackVerificationCache.lookup(
                cacheJson = encoded,
                remoteName = "hle-provider-kuwo-2.hlp",
                packSha256 = "b".repeat(64),
            ),
        )
        assertNull(
            ProviderPackVerificationCache.lookup(
                cacheJson = encoded,
                remoteName = "hle-provider-kuwo-3.hlp",
                packSha256 = "a".repeat(64),
            ),
        )
    }

    @Test
    fun corruptedCacheFallsBackToFullVerification() {
        assertNull(
            ProviderPackVerificationCache.lookup(
                cacheJson = "{not-json",
                remoteName = "hle-provider-kuwo-2.hlp",
                packSha256 = "a".repeat(64),
            ),
        )
    }

    @Test
    fun recordReplacesTheSameFileAndKeepsOtherFiles() {
        val first = ProviderPackVerificationCache.record(
            cacheJson = null,
            remoteName = "hle-provider-kuwo-1.hlp",
            packSha256 = "a".repeat(64),
            manifest = manifest(versionCode = 1),
        )
        val second = ProviderPackVerificationCache.record(
            cacheJson = first,
            remoteName = "hle-provider-kuwo-2.hlp",
            packSha256 = "b".repeat(64),
            manifest = manifest(versionCode = 2),
        )
        val replaced = ProviderPackVerificationCache.record(
            cacheJson = second,
            remoteName = "hle-provider-kuwo-1.hlp",
            packSha256 = "c".repeat(64),
            manifest = manifest(versionCode = 3),
        )

        assertNull(
            ProviderPackVerificationCache.lookup(
                replaced,
                "hle-provider-kuwo-1.hlp",
                "a".repeat(64),
            ),
        )
        assertEquals(
            3,
            ProviderPackVerificationCache.lookup(
                replaced,
                "hle-provider-kuwo-1.hlp",
                "c".repeat(64),
            )?.versionCode,
        )
        assertEquals(
            2,
            ProviderPackVerificationCache.lookup(
                replaced,
                "hle-provider-kuwo-2.hlp",
                "b".repeat(64),
            )?.versionCode,
        )
    }

    @Test
    fun retainDropsFilesThatAreNoLongerInstalled() {
        val first = ProviderPackVerificationCache.record(
            cacheJson = null,
            remoteName = "hle-provider-kuwo-1.hlp",
            packSha256 = "a".repeat(64),
            manifest = manifest(versionCode = 1),
        )
        val second = ProviderPackVerificationCache.record(
            cacheJson = first,
            remoteName = "hle-provider-kuwo-2.hlp",
            packSha256 = "b".repeat(64),
            manifest = manifest(versionCode = 2),
        )

        val retained = requireNotNull(
            ProviderPackVerificationCache.retain(
                cacheJson = second,
                remoteNames = setOf("hle-provider-kuwo-2.hlp"),
            ),
        )

        assertNull(
            ProviderPackVerificationCache.lookup(
                retained,
                "hle-provider-kuwo-1.hlp",
                "a".repeat(64),
            ),
        )
        assertEquals(
            2,
            ProviderPackVerificationCache.lookup(
                retained,
                "hle-provider-kuwo-2.hlp",
                "b".repeat(64),
            )?.versionCode,
        )
    }

    private fun manifest(versionCode: Int) = ProviderPackManifest(
        schemaVersion = 1,
        pluginApiVersion = OfficialProviderCatalog.PLUGIN_API_VERSION,
        pluginId = "kuwo",
        displayName = "酷我音乐",
        versionName = "1.0.$versionCode",
        versionCode = versionCode,
        minCoreVersionCode = 0,
        entryClass = "com.juren233.hle.providers.kuwo.KuwoPluginEntry",
        providerPackageName = "com.juren233.hyperlyricsenhanced.provider.kuwo",
        targetPackages = listOf("cn.kuwo.player"),
        classesSha256 = "0".repeat(64),
        dexSha256 = mapOf("classes.dex" to "0".repeat(64)),
    )
}

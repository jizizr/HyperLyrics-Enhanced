/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderReleaseSelectionTest {

    private fun release(
        versionCode: Int,
        pluginApiVersion: Int = OfficialProviderCatalog.PLUGIN_API_VERSION,
        minCoreVersionCode: Int = 0,
    ) = ProviderCatalogRelease(
        versionCode = versionCode,
        versionName = "1.0.$versionCode",
        assetUrl = "https://github.com/juren233/HLE-Providers/releases/download/v$versionCode/netease.hlp",
        sha256 = "a".repeat(64),
        pluginApiVersion = pluginApiVersion,
        minCoreVersionCode = minCoreVersionCode,
    )

    private fun entry(
        releases: List<ProviderCatalogRelease> = emptyList(),
        revoked: List<Int> = emptyList(),
        available: Boolean = releases.isEmpty(),
        versionCode: Int? = 28,
    ) = ProviderCatalogEntry(
        id = "netease",
        displayName = "网易云音乐",
        targetPackages = listOf("com.netease.cloudmusic"),
        available = available,
        versionName = "1.0.$versionCode",
        versionCode = versionCode,
        assetUrl = if (available) "https://github.com/juren233/HLE-Providers/releases/download/v$versionCode/netease.hlp" else null,
        sha256 = if (available) "a".repeat(64) else null,
        releases = releases,
        revokedVersionCodes = revoked,
    )

    @Test
    fun selectsNewestCompatibleRelease() {
        val e = entry(
            releases = listOf(
                release(30, minCoreVersionCode = 999999),
                release(29),
                release(28),
            ),
        )

        val selected = ProviderReleaseSelection.selectLatestCompatible(e, coreVersionCode = 100)

        assertEquals(29, selected?.versionCode)
    }

    @Test
    fun filtersApiVersionsOutsideTheSupportedRange() {
        val e = entry(
            releases = listOf(
                release(31, pluginApiVersion = OfficialProviderCatalog.PLUGIN_API_VERSION + 1),
                release(30, pluginApiVersion = OfficialProviderCatalog.PLUGIN_API_MIN_SUPPORTED),
            ),
        )

        val selected = ProviderReleaseSelection.selectLatestCompatible(e, coreVersionCode = 100)

        assertEquals(30, selected?.versionCode)
    }

    @Test
    fun skipsRevokedAndBadVersions() {
        val e = entry(
            releases = listOf(release(30), release(29), release(28)),
            revoked = listOf(30),
        )

        val selected = ProviderReleaseSelection.selectLatestCompatible(
            e,
            coreVersionCode = 100,
            badVersionCodes = setOf(29),
        )

        assertEquals(28, selected?.versionCode)
    }

    @Test
    fun fallsBackToTopLevelFieldsWhenReleasesMissing() {
        val e = entry(available = true, versionCode = 27)

        val selected = ProviderReleaseSelection.selectLatestCompatible(e, coreVersionCode = 100)

        assertEquals(27, selected?.versionCode)
        assertEquals("1.0.27", selected?.versionName)
    }

    @Test
    fun returnsNullForUnpublishedProvider() {
        val e = entry(available = false, versionCode = null).copy(
            assetUrl = null,
            sha256 = null,
            versionName = null,
        )

        assertNull(ProviderReleaseSelection.selectLatestCompatible(e, coreVersionCode = 100))
        assertNull(ProviderReleaseSelection.asTopLevelRelease(e))
    }

    @Test
    fun coreUpdateRequiredOnlyWhenAllNewerReleasesNeedNewerCore() {
        val needsNewerCore = entry(
            releases = listOf(
                release(30, minCoreVersionCode = 999999),
                release(28),
            ),
        )
        val hasCompatibleNewer = entry(
            releases = listOf(
                release(30),
                release(28),
            ),
        )

        assertTrue(
            ProviderReleaseSelection.coreUpdateRequired(needsNewerCore, 28, coreVersionCode = 100),
        )
        assertFalse(
            ProviderReleaseSelection.coreUpdateRequired(hasCompatibleNewer, 28, coreVersionCode = 100),
        )
    }

    @Test
    fun coreUpdateRequiredFalseWhenNoNewerRelease() {
        val e = entry(releases = listOf(release(28)))

        assertFalse(
            ProviderReleaseSelection.coreUpdateRequired(e, 28, coreVersionCode = 100),
        )
    }

    @Test
    fun revokedNewestReleaseDoesNotTriggerCoreUpdateHint() {
        // 30 已撤回且要求更高主程序，但兼容的 29 仍可安装，不应提示更新 App
        val e = entry(
            releases = listOf(
                release(30, minCoreVersionCode = 999999),
                release(29),
            ),
            revoked = listOf(30),
        )

        assertFalse(
            ProviderReleaseSelection.coreUpdateRequired(e, 28, coreVersionCode = 100),
        )
    }
}

class ProviderAutoUpdateDecisionTest {

    @Test
    fun upgradesWhenCandidateIsNewer() {
        assertTrue(ProviderAutoUpdateDecision.shouldReplace(28, 29, emptySet()))
    }

    @Test
    fun skipsWhenVersionsMatch() {
        assertFalse(ProviderAutoUpdateDecision.shouldReplace(29, 29, emptySet()))
    }

    @Test
    fun skipsDowngradeWhenInstalledVersionIsHealthy() {
        assertFalse(ProviderAutoUpdateDecision.shouldReplace(29, 28, emptySet()))
    }

    @Test
    fun downgradesWhenInstalledVersionIsMarkedBad() {
        assertTrue(ProviderAutoUpdateDecision.shouldReplace(29, 28, setOf(29)))
    }
}

/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class IslandTouchSystemUiContextTest {
    private data class Ctx(val pkg: String?)

    private val plugin = Ctx("miui.systemui.plugin")
    private val systemUi = Ctx(IslandTouchSystemUiContext.SYSTEM_UI_PACKAGE)

    private fun select(candidates: List<Ctx?>) = IslandTouchSystemUiContext.select(plugin, candidates, Ctx::pkg)

    @Test
    fun systemUiPackageIsTheOneTheServerAccepts() {
        assertEquals("com.android.systemui", IslandTouchSystemUiContext.SYSTEM_UI_PACKAGE)
    }

    @Test
    fun picksTheCandidateReportingTheSystemUiPackage() {
        assertSame(systemUi, select(listOf(systemUi)))
    }

    @Test
    fun skipsMissingAndPluginCandidates() {
        assertSame(systemUi, select(listOf(null, Ctx("miui.systemui.plugin"), systemUi)))
    }

    @Test
    fun usesTheFirstSystemUiCandidate() {
        val first = Ctx(IslandTouchSystemUiContext.SYSTEM_UI_PACKAGE)
        val second = Ctx(IslandTouchSystemUiContext.SYSTEM_UI_PACKAGE)
        assertSame(first, select(listOf(first, second)))
    }

    @Test
    fun fallsBackToHostWhenNoCandidateMatches() {
        assertSame(plugin, select(listOf(null, Ctx("other.package"), Ctx(null))))
        assertSame(plugin, select(emptyList()))
    }
}

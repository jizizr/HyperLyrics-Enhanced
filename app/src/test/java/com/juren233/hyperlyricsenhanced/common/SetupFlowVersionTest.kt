/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import com.juren233.hyperlyricsenhanced.common.SetupFlowVersion.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupFlowVersionTest {

    private val v830 = Version(8, 3, 0)

    @Test
    fun parsesPlainAndDecoratedVersionNames() {
        assertEquals(v830, SetupFlowVersion.parse("8.3.0"))
        assertEquals(v830, SetupFlowVersion.parse("v8.3.0"))
        assertEquals(v830, SetupFlowVersion.parse("8.3.0-canary"))
        assertEquals(v830, SetupFlowVersion.parse(" 8.3.0+120 "))
        assertEquals(v830, SetupFlowVersion.parse("8.3"))
    }

    @Test
    fun rejectsMalformedVersionNames() {
        assertNull(SetupFlowVersion.parse(null))
        assertNull(SetupFlowVersion.parse(""))
        assertNull(SetupFlowVersion.parse("8"))
        assertNull(SetupFlowVersion.parse("8.3.0.1"))
        assertNull(SetupFlowVersion.parse("8.x.0"))
        assertNull(SetupFlowVersion.parse("8.-3.0"))
    }

    @Test
    fun comparesEachSegmentNumerically() {
        assertTrue(Version(8, 3, 1) > Version(8, 3, 0))
        assertTrue(Version(8, 4, 0) > Version(8, 3, 9))
        assertTrue(Version(9, 0, 0) > Version(8, 99, 99))
        // 旧的「主×10＋次」写法在次版本到 10 时与下一个主版本撞车；逐段比较不会。
        assertTrue(Version(8, 10, 0) > Version(8, 9, 0))
        assertTrue(Version(9, 0, 0) > Version(8, 10, 0))
    }

    @Test
    fun restoresLegacyIntegerAsMajorMinor() {
        assertEquals(v830, SetupFlowVersion.fromLegacy(83))
        assertEquals(Version(7, 4, 0), SetupFlowVersion.fromLegacy(74))
        assertNull(SetupFlowVersion.fromLegacy(0))
    }

    @Test
    fun newKeyWinsAndLegacyIsOnlyAFallback() {
        assertEquals(Version(8, 3, 1), SetupFlowVersion.resolveStored("8.3.1", 83))
        assertEquals(v830, SetupFlowVersion.resolveStored(null, 83))
        assertEquals(v830, SetupFlowVersion.resolveStored("garbage", 83))
        assertNull(SetupFlowVersion.resolveStored(null, null))
    }

    @Test
    fun completionRequiresStoredVersionAtLeastCurrent() {
        assertFalse(SetupFlowVersion.isCompleted(null, v830))
        assertFalse(SetupFlowVersion.isCompleted(Version(8, 2, 9), v830))
        assertTrue(SetupFlowVersion.isCompleted(v830, v830))
        assertTrue(SetupFlowVersion.isCompleted(Version(8, 3, 1), v830))
        // 流程在 8.3.1 又改过：停在 8.3.0 的用户需要重走。
        assertFalse(SetupFlowVersion.isCompleted(v830, Version(8, 3, 1)))
    }

    @Test
    fun legacyCompletedUsersAreNotAskedAgainAtTheSameFlowVersion() {
        val stored = SetupFlowVersion.resolveStored(versionName = null, legacyValue = 83)
        assertTrue(SetupFlowVersion.isCompleted(stored, SetupFlowVersion.CURRENT))
    }

    @Test
    fun rendersAsDottedTriple() {
        assertEquals("8.3.0", v830.toString())
        assertEquals(v830, SetupFlowVersion.parse(v830.toString()))
    }
}

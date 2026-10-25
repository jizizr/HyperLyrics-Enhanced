/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLoadMarkerTest {

    private val nowMs = 1_800_000_000_000L

    @Test
    fun encodeDecodeRoundTrip() {
        val state = ProviderLoadMarker.MarkerState(
            versionCode = 29,
            uncleanCount = 2,
            lastAttemptAtMs = nowMs,
        )

        assertEquals(state, ProviderLoadMarker.decode(ProviderLoadMarker.encode(state)))
    }

    @Test
    fun decodeRejectsMalformedValues() {
        assertNull(ProviderLoadMarker.decode(null))
        assertNull(ProviderLoadMarker.decode(""))
        assertNull(ProviderLoadMarker.decode("29"))
        assertNull(ProviderLoadMarker.decode("29:2"))
        assertNull(ProviderLoadMarker.decode("29:x:$nowMs"))
        assertNull(ProviderLoadMarker.decode("x:2:$nowMs"))
        assertNull(ProviderLoadMarker.decode("0:2:$nowMs"))
        assertNull(ProviderLoadMarker.decode("29:0:$nowMs"))
        assertNull(ProviderLoadMarker.decode("29:2:0"))
        assertNull(ProviderLoadMarker.decode("29:2:${nowMs}:1"))
    }

    @Test
    fun firstLoadOfVersionStartsCountingAtOne() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = null,
            previousVersionCode = 28,
            nowMs = nowMs,
        )

        assertTrue(decision is ProviderLoadMarker.Decision.LoadNormally)
        decision as ProviderLoadMarker.Decision.LoadNormally
        assertEquals(29, decision.versionCode)
        assertEquals(
            ProviderLoadMarker.MarkerState(29, 1, nowMs),
            decision.newMarker,
        )
    }

    @Test
    fun staleMarkerOfOlderVersionRestartsCounting() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 30,
            marker = ProviderLoadMarker.MarkerState(29, 2, nowMs),
            previousVersionCode = 28,
            nowMs = nowMs,
        )

        assertTrue(decision is ProviderLoadMarker.Decision.LoadNormally)
        decision as ProviderLoadMarker.Decision.LoadNormally
        assertEquals(ProviderLoadMarker.MarkerState(30, 1, nowMs), decision.newMarker)
    }

    @Test
    fun uncleanRelaunchIncrementsCount() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = ProviderLoadMarker.MarkerState(29, 1, nowMs - 60_000L),
            previousVersionCode = 28,
            nowMs = nowMs,
        )

        assertTrue(decision is ProviderLoadMarker.Decision.LoadNormally)
        decision as ProviderLoadMarker.Decision.LoadNormally
        assertEquals(ProviderLoadMarker.MarkerState(29, 2, nowMs), decision.newMarker)
    }

    @Test
    fun rollsBackAfterMaxStrikesWithPreviousVersion() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = ProviderLoadMarker.MarkerState(29, 2, nowMs - 60_000L),
            previousVersionCode = 28,
            nowMs = nowMs,
        )

        assertTrue(decision is ProviderLoadMarker.Decision.Rollback)
        decision as ProviderLoadMarker.Decision.Rollback
        assertEquals(29, decision.badVersionCode)
        assertEquals(28, decision.fallbackVersionCode)
    }

    @Test
    fun rollbackWithoutPreviousVersionSkipsPlugin() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = ProviderLoadMarker.MarkerState(29, 2, nowMs - 60_000L),
            previousVersionCode = 0,
            nowMs = nowMs,
        )

        assertTrue(decision is ProviderLoadMarker.Decision.Rollback)
        decision as ProviderLoadMarker.Decision.Rollback
        assertNull(decision.fallbackVersionCode)
    }

    @Test
    fun customStrikeLimitIsRespected() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = ProviderLoadMarker.MarkerState(29, 1, nowMs - 60_000L),
            previousVersionCode = 28,
            maxStrikes = 1,
            nowMs = nowMs,
        )

        assertTrue(decision is ProviderLoadMarker.Decision.Rollback)
    }

    @Test
    fun decayedStrikesRestartCountingInsteadOfRollingBack() {
        // 两次异常发生在很久以前（超过衰减窗口）：重装同版本不应直接回退。
        val staleAt = nowMs - ProviderLoadMarker.STRIKE_DECAY_MS - 1
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = ProviderLoadMarker.MarkerState(29, 2, staleAt),
            previousVersionCode = 28,
            nowMs = nowMs,
        )

        assertTrue(decision is ProviderLoadMarker.Decision.LoadNormally)
        decision as ProviderLoadMarker.Decision.LoadNormally
        assertEquals(ProviderLoadMarker.MarkerState(29, 1, nowMs), decision.newMarker)
    }

    @Test
    fun freshStrikeAfterDecayCountsFromOne() {
        val staleAt = nowMs - ProviderLoadMarker.STRIKE_DECAY_MS - 1
        val first = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = ProviderLoadMarker.MarkerState(29, 2, staleAt),
            previousVersionCode = 28,
            nowMs = nowMs,
        ) as ProviderLoadMarker.Decision.LoadNormally
        val second = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = first.newMarker,
            previousVersionCode = 28,
            nowMs = nowMs + 60_000L,
        )

        // 衰减后重新计 1，本次再异常升到 2，仍未达回退阈值（阈值 2 会在下一次判定触发）。
        assertEquals(1, first.newMarker.uncleanCount)
        assertTrue(second is ProviderLoadMarker.Decision.LoadNormally)
        assertEquals(2, (second as ProviderLoadMarker.Decision.LoadNormally).newMarker.uncleanCount)
    }

    @Test
    fun recordedBadVersionStaysRolledBackAfterMarkerDecay() {
        val staleAt = nowMs - ProviderLoadMarker.STRIKE_DECAY_MS - 1
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = ProviderLoadMarker.MarkerState(29, 2, staleAt),
            previousVersionCode = 28,
            badVersionCodes = setOf(29),
            nowMs = nowMs,
        )

        assertEquals(ProviderLoadMarker.Decision.Rollback(29, 28), decision)
    }

    @Test
    fun recordedBadVersionRollsBackWithoutMarker() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 29,
            marker = null,
            previousVersionCode = 28,
            badVersionCodes = setOf(29),
            nowMs = nowMs,
        )

        assertEquals(ProviderLoadMarker.Decision.Rollback(29, 28), decision)
    }

    @Test
    fun badPreviousVersionIsNotUsedAsFallback() {
        val decision = ProviderLoadMarker.decide(
            pendingVersionCode = 28,
            marker = ProviderLoadMarker.MarkerState(28, 2, nowMs),
            previousVersionCode = 29,
            badVersionCodes = setOf(29),
            nowMs = nowMs,
        )

        assertEquals(ProviderLoadMarker.Decision.Rollback(28, null), decision)
    }
}

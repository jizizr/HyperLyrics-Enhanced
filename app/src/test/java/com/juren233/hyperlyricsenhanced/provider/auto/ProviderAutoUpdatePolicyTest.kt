/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAutoUpdatePolicyTest {

    private val hourMs = 3_600_000L
    private val nowMs = 1_800_000_000_000L

    @Test
    fun offNeverTriggersAppOpenCheck() {
        assertFalse(
            ProviderAutoUpdatePolicy.shouldCheckOnAppOpen(
                intervalHours = ProviderAutoUpdatePolicy.INTERVAL_OFF,
                nowMs = nowMs,
                lastCheckMs = 0L,
            ),
        )
    }

    @Test
    fun periodicOnlyNeverTriggersAppOpenCheck() {
        // 选择 24 小时：完全交给后台周期，无论间隔多久打开 App 都不额外触发。
        assertFalse(
            ProviderAutoUpdatePolicy.shouldCheckOnAppOpen(
                intervalHours = ProviderAutoUpdatePolicy.INTERVAL_PERIODIC_ONLY,
                nowMs = nowMs,
                lastCheckMs = nowMs - 7 * 24 * hourMs,
            ),
        )
    }

    @Test
    fun triggersOnlyAfterSelectedIntervalElapsed() {
        assertFalse(
            ProviderAutoUpdatePolicy.shouldCheckOnAppOpen(
                intervalHours = 6,
                nowMs = nowMs,
                lastCheckMs = nowMs - 6 * hourMs + 60_000L,
            ),
        )
        assertTrue(
            ProviderAutoUpdatePolicy.shouldCheckOnAppOpen(
                intervalHours = 6,
                nowMs = nowMs,
                lastCheckMs = nowMs - 6 * hourMs,
            ),
        )
    }

    @Test
    fun shorterIntervalsCheckSooner() {
        assertTrue(
            ProviderAutoUpdatePolicy.shouldCheckOnAppOpen(
                intervalHours = 1,
                nowMs = nowMs,
                lastCheckMs = nowMs - hourMs - 1_000L,
            ),
        )
        assertFalse(
            ProviderAutoUpdatePolicy.shouldCheckOnAppOpen(
                intervalHours = 12,
                nowMs = nowMs,
                lastCheckMs = nowMs - hourMs - 1_000L,
            ),
        )
    }

    @Test
    fun firstLaunchTriggersImmediatelyForNonPeriodicOnlyChoices() {
        assertTrue(
            ProviderAutoUpdatePolicy.shouldCheckOnAppOpen(
                intervalHours = 6,
                nowMs = nowMs,
                lastCheckMs = 0L,
            ),
        )
    }

    @Test
    fun choiceListCoversAllUiOptions() {
        assertEquals(
            listOf(0, 1, 2, 6, 12, 24),
            ProviderAutoUpdatePolicy.CHOICE_HOURS,
        )
    }
}

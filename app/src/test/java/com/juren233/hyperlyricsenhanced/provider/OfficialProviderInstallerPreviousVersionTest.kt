/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialProviderInstallerPreviousVersionTest {

    private fun previous(installed: Int, recorded: Int, new: Int) =
        OfficialProviderInstaller.previousVersionAfterInstall(installed, recorded, new)

    @Test
    fun upgradeKeepsInstalledVersionAsPrevious() {
        assertEquals(28, previous(installed = 28, recorded = 27, new = 29))
    }

    @Test
    fun reinstallingSameVersionKeepsRecordedPrevious() {
        assertEquals(27, previous(installed = 28, recorded = 27, new = 28))
    }

    @Test
    fun downgradeFromBadVersionNeverPointsBackToIt() {
        assertEquals(27, previous(installed = 29, recorded = 27, new = 28))
        assertEquals(0, previous(installed = 29, recorded = 28, new = 28))
    }

    @Test
    fun firstInstallHasNoPrevious() {
        assertEquals(0, previous(installed = 0, recorded = 0, new = 1))
    }
}

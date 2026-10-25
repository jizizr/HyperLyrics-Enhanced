/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextTrackMetadataCacheTest {
    @Test
    fun `official next-track frames follow Pack enabled state`() {
        val providerPackageName =
            "com.juren233.hyperlyricsenhanced.provider.salt-player"
        val playerPackageName = "com.salt.music"

        assertTrue(
            NextTrackMetadataCache.isProviderAccepted(
                providerPackageName,
                playerPackageName,
                officialProviderPreference = true,
            ),
        )
        assertFalse(
            NextTrackMetadataCache.isProviderAccepted(
                providerPackageName,
                playerPackageName,
                officialProviderPreference = false,
            ),
        )
        assertTrue(
            NextTrackMetadataCache.isProviderAccepted(
                providerPackageName,
                playerPackageName,
                officialProviderPreference = null,
            ),
        )
    }

    @Test
    fun `legacy or mismatched Provider cannot use official next-track channel`() {
        assertFalse(
            NextTrackMetadataCache.isProviderAccepted(
                "io.github.proify.lyricon.saltprovider",
                "com.salt.music",
                officialProviderPreference = true,
            ),
        )
        assertFalse(
            NextTrackMetadataCache.isProviderAccepted(
                "com.juren233.hyperlyricsenhanced.provider.salt-player",
                "cn.kuwo.player",
                officialProviderPreference = true,
            ),
        )
    }

    @Test
    fun `built in Salt control channel is accepted without Pack preference`() {
        assertTrue(
            NextTrackMetadataCache.isProviderAccepted(
                "com.juren233.hyperlyricsenhanced",
                "com.salt.music",
                officialProviderPreference = false,
            ),
        )
    }

    @Test
    fun `lookup keys put the session text first and the lyric side name second`() {
        val keys = NextTrackMetadataCache.currentTrackKeys(
            currentId = null,
            currentTitle = "就这么朝着未来前进了",
            currentArtist = "五月天-后来的我们",
            lyricTitle = "后来的我们",
            lyricArtist = "五月天",
        )

        assertEquals(
            listOf(
                CurrentTrackKey(null, "就这么朝着未来前进了", "五月天-后来的我们"),
                CurrentTrackKey(null, "后来的我们", "五月天"),
            ),
            keys,
        )
    }

    @Test
    fun `lookup keys ignore a missing or blank lyric side name`() {
        val session = CurrentTrackKey("id", "Title", "Artist")

        assertEquals(
            listOf(session),
            NextTrackMetadataCache.currentTrackKeys("id", "Title", "Artist", null, "Artist"),
        )
        assertEquals(
            listOf(session),
            NextTrackMetadataCache.currentTrackKeys("id", "Title", "Artist", "   ", "Artist"),
        )
    }

    @Test
    fun `lookup keys trim the lyric side text and tolerate a missing lyric artist`() {
        assertEquals(
            listOf(
                CurrentTrackKey("id", "lyric", "x"),
                CurrentTrackKey("id", "Song", ""),
            ),
            NextTrackMetadataCache.currentTrackKeys("id", "lyric", "x", "  Song ", null),
        )
    }

    @Test
    fun `lookup keys do not repeat the session key when the lyric side agrees`() {
        assertEquals(
            listOf(CurrentTrackKey(null, "Song", "Artist")),
            NextTrackMetadataCache.currentTrackKeys(null, "Song", "Artist", "Song", "Artist"),
        )
    }
}

/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextTrackMetadataStoreTest {
    private var now = 1_000L
    private val store = NextTrackMetadataStore(nowMs = { now }, ttlMs = 5_000L)

    @Test
    fun `matches official metadata by current media id`() {
        assertTrue(
            store.update(
                playerPackageName = "com.tencent.qqmusic",
                currentId = "1",
                currentTitle = "Current",
                currentArtist = "Artist",
                metadata = NextTrackMetadata("2", "Next", "Next Artist", "Album", 10_000L),
            )
        )

        assertEquals(
            "Next",
            store.find("com.tencent.qqmusic", "1", "Different title", "Different artist")?.title,
        )
        assertNull(store.find("com.tencent.qqmusic", "9", "Other", "Artist"))
    }

    @Test
    fun `falls back to normalized title and artist identity`() {
        store.update(
            playerPackageName = "com.netease.cloudmusic",
            currentId = "",
            currentTitle = "  Current   Song ",
            currentArtist = "Artist",
            metadata = NextTrackMetadata("2", "Next", "Singer", "", -1L),
        )

        assertEquals(
            "Next",
            store.find("com.netease.cloudmusic", null, "current song", "artist")?.title,
        )
    }

    @Test
    fun `keeps different current songs isolated and expires stale data`() {
        store.update(
            "com.tencent.qqmusic",
            "1",
            "First",
            "Artist",
            NextTrackMetadata("2", "Second", "Artist", "", -1L),
        )
        store.update(
            "com.tencent.qqmusic",
            "2",
            "Second",
            "Artist",
            NextTrackMetadata("3", "Third", "Artist", "", -1L),
        )

        assertEquals("Second", store.find("com.tencent.qqmusic", "1", "", "")?.title)
        assertEquals("Third", store.find("com.tencent.qqmusic", "2", "", "")?.title)
        now += 5_001L
        assertNull(store.find("com.tencent.qqmusic", "2", "Second", "Artist"))
    }

    @Test
    fun `clear only removes the matching current identity`() {
        store.update(
            "com.tencent.qqmusic",
            "1",
            "First",
            "Artist",
            NextTrackMetadata("2", "Second", "Artist", "", -1L),
        )
        store.update(
            "com.tencent.qqmusic",
            "2",
            "Second",
            "Artist",
            NextTrackMetadata("3", "Third", "Artist", "", -1L),
        )

        store.clear("com.tencent.qqmusic", "1", "First", "Artist")

        assertNull(store.find("com.tencent.qqmusic", "1", "First", "Artist"))
        assertEquals("Third", store.find("com.tencent.qqmusic", "2", "Second", "Artist")?.title)
    }

    @Test
    fun `finds the next song through the lyric side name when the session text is a lyric line`() {
        // 酷狗：会话标题是歌词行、歌手是「歌手-歌名」，控制帧用 Provider 还原后的歌名/歌手。
        store.update(
            playerPackageName = "com.kugou.android",
            currentId = "kugou:abc",
            currentTitle = "后来的我们",
            currentArtist = "五月天",
            metadata = NextTrackMetadata("def", "倔强", "五月天", "", -1L),
        )
        val sessionKey = CurrentTrackKey(null, "就这么朝着未来前进了", "五月天-后来的我们")
        val lyricKey = CurrentTrackKey(null, "后来的我们", "五月天")

        assertNull(store.findFirst("com.kugou.android", listOf(sessionKey)))
        assertEquals("倔强", store.findFirst("com.kugou.android", listOf(sessionKey, lyricKey))?.title)
    }

    @Test
    fun `session key wins over the lyric side key when both match`() {
        store.update("com.kugou.android", "", "Session Song", "Artist", NextTrackMetadata("2", "From Session", "A", "", -1L))
        store.update("com.kugou.android", "", "Lyric Song", "Artist", NextTrackMetadata("3", "From Lyric", "A", "", -1L))

        assertEquals(
            "From Session",
            store.findFirst(
                "com.kugou.android",
                listOf(
                    CurrentTrackKey(null, "Session Song", "Artist"),
                    CurrentTrackKey(null, "Lyric Song", "Artist"),
                ),
            )?.title,
        )
    }

    @Test
    fun `lyric side key does not cross players and respects expiry`() {
        store.update("com.kugou.android", "", "Song", "Artist", NextTrackMetadata("2", "Next", "A", "", -1L))
        val keys = listOf(
            CurrentTrackKey(null, "lyric line", "Artist-Song"),
            CurrentTrackKey(null, "Song", "Artist"),
        )

        assertNull(store.findFirst("com.netease.cloudmusic", keys))
        now += 5_001L
        assertNull(store.findFirst("com.kugou.android", keys))
    }

    @Test
    fun `an empty key list finds nothing`() {
        store.update("com.kugou.android", "", "Song", "Artist", NextTrackMetadata("2", "Next", "A", "", -1L))

        assertNull(store.findFirst("com.kugou.android", emptyList()))
    }
}

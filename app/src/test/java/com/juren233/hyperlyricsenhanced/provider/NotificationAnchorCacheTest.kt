/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import com.juren233.hyperlyricsenhanced.common.media.AnchorSource
import com.juren233.hyperlyricsenhanced.common.media.TrackAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationAnchorCacheTest {
    private var queries = 0
    private var notificationTitle: String? = "跟佢做个 FRIEND"
    private val cache = NotificationAnchorCache<String> {
        queries++
        listOfNotNull(notificationTitle?.let { TrackAnchor(AnchorSource.NOTIFICATION, title = it) })
    }

    private fun read(rawTitle: String, nowMs: Long, trackToken: String = "1", session: String = "s") =
        cache.anchors(session, trackToken, rawTitle, nowMs)

    @Test
    fun `drifting session title reads the notification once per settled segment`() {
        read("跟佢做个 FRIEND", nowMs = 0L)
        read("第一句歌词", nowMs = 1_500L)
        repeat(10) { read("歌词 $it", nowMs = 3_000L + it * 2_000L) }

        assertEquals(2, queries)
        assertEquals("跟佢做个 FRIEND", read("又一句", nowMs = 25_000L).single().title)
    }

    @Test
    fun `results within the settle window are not cached`() {
        // 换曲后通知可能还是上一首：起始窗口内每次都重新读取。
        notificationTitle = "上一首"
        read("第一句歌词", nowMs = 0L)
        notificationTitle = "跟佢做个 FRIEND"
        assertEquals("跟佢做个 FRIEND", read("第二句歌词", nowMs = 500L).single().title)
        assertEquals(2, queries)
    }

    @Test
    fun `a notification that echoes the session title is never cached`() {
        // 通知由同一份会话元数据生成时随歌词漂移，缓存会把旧歌词行当作歌名。
        listOf("A", "B", "C").forEachIndexed { index, line ->
            notificationTitle = line
            assertEquals(line, read(line, nowMs = 2_000L + index * 2_000L).single().title)
        }
        assertEquals(3, queries)
    }

    @Test
    fun `empty results are not cached`() {
        notificationTitle = null
        read("歌词 1", nowMs = 2_000L)
        notificationTitle = "跟佢做个 FRIEND"
        assertEquals("跟佢做个 FRIEND", read("歌词 2", nowMs = 4_000L).single().title)
        assertEquals(2, queries)
    }

    @Test
    fun `a new track segment or session does not reuse the cache`() {
        read("歌词 1", nowMs = 0L)
        read("歌词 2", nowMs = 2_000L)
        read("歌词 3", nowMs = 4_000L)
        assertEquals(2, queries)

        notificationTitle = "Glitch"
        assertEquals("Glitch", read("新歌歌词", nowMs = 5_000L, trackToken = "2").single().title)
        read("新歌歌词", nowMs = 5_100L, trackToken = "2", session = "other")
        assertEquals(4, queries)
    }

    @Test
    fun `cached anchors expire after the maximum age`() {
        read("歌词 1", nowMs = 0L)
        read("歌词 2", nowMs = 2_000L)
        read("歌词 3", nowMs = 2_000L + NotificationAnchorCache.MAX_AGE_MS - 1)
        assertEquals(2, queries)

        notificationTitle = "原名"
        assertEquals("原名", read("歌词 4", nowMs = 2_000L + NotificationAnchorCache.MAX_AGE_MS).single().title)
        assertEquals(3, queries)
    }
}

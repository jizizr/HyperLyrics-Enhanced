/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.root.timeline

import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.source.TimelineContent
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 被车载歌词改写的会话（标题留空、歌手是单曲内稳定的原始组合串）与干净的歌词内容之间的匹配。
 * 夹具来自 2026-10-26 Salt Player 真机取证和小米音乐的已知形态。
 */
class PollutedTrackIdentityTest {
    private val saltAnchor = TrackIdentity(
        packageName = SALT,
        title = "",
        artist = "许冠杰 - 跟佢做个 FRIEND",
        album = "最佳拍档大显神通",
        durationMs = 200_000L,
        polluted = true,
    )

    private fun content(title: String, artist: String, album: String = "", durationMs: Long = 0L) =
        TrackIdentity(
            packageName = SALT,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
        )

    @Test
    fun `clean lyric content matches the polluted session in either argument order`() {
        val song = content("跟佢做个 FRIEND", "许冠杰")

        assertTrue(song.matchesAvailableFields(saltAnchor))
        assertTrue(saltAnchor.matchesAvailableFields(song))
    }

    @Test
    fun `title-dash-artist form matches too`() {
        val anchor = saltAnchor.copy(artist = "Love Somebody-LAUV", album = "")

        assertTrue(content("Love Somebody", "LAUV").matchesAvailableFields(anchor))
    }

    @Test
    fun `another song by the same artist does not match a combined session artist`() {
        assertFalse(content("另一首歌", "许冠杰").matchesAvailableFields(saltAnchor))
    }

    @Test
    fun `conflicting known albums never match`() {
        assertFalse(
            content("跟佢做个 FRIEND", "许冠杰", album = "别的专辑").matchesAvailableFields(saltAnchor),
        )
    }

    @Test
    fun `title-only pollution needs the album or a close duration besides the artist`() {
        val anchor = TrackIdentity(
            packageName = SALT,
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L,
            polluted = true,
        )

        assertFalse(content("Song", "Artist").matchesAvailableFields(anchor.copy(album = "")))
        assertTrue(content("Song", "Artist", album = "Album").matchesAvailableFields(anchor))
        assertTrue(
            content("Song", "Artist", durationMs = 203_000L).matchesAvailableFields(anchor.copy(album = "")),
        )
        assertFalse(
            content("Song", "Artist", durationMs = 230_000L).matchesAvailableFields(anchor.copy(album = "")),
        )
    }

    @Test
    fun `media id still decides when both sides have one`() {
        val anchor = saltAnchor.copy(mediaId = "m1")

        assertTrue(content("无关", "无关").copy(mediaId = "m1").matchesAvailableFields(anchor))
        assertFalse(content("跟佢做个 FRIEND", "许冠杰").copy(mediaId = "m2").matchesAvailableFields(anchor))
    }

    @Test
    fun `polluted identity key does not depend on any lyric line`() {
        // 标题在 TrackIdentity 里留空，所以不同歌词行得到同一个键。
        assertEquals(saltAnchor.normalizedKey(), saltAnchor.copy().normalizedKey())
        assertFalse(saltAnchor.normalizedKey() == content("跟佢做个 FRIEND", "许冠杰").normalizedKey())
    }

    @Test
    fun `display fields never expose the raw combined artist`() {
        assertEquals("", saltAnchor.displayTitle)
        assertEquals("", saltAnchor.displayArtist)
        val clean = content("Song", "Artist")
        assertEquals("Song", clean.displayTitle)
        assertEquals("Artist", clean.displayArtist)
    }

    @Test
    fun `timeline accepts clean content for a polluted anchor and shows the song's own names`() {
        val song = Song(name = "跟佢做个 FRIEND", artist = "许冠杰")
        val timelineContent = TimelineContent(
            sourceId = "lyricon",
            track = content("跟佢做个 FRIEND", "许冠杰"),
            song = song,
        )

        assertEquals(
            TimelineContentPolicy.Decision.APPLY,
            TimelineContentPolicy.decide("lyricon", saltAnchor, timelineContent),
        )
        assertEquals(
            TimelineContentPolicy.DisplayMetadata("跟佢做个 FRIEND", "许冠杰"),
            TimelineContentPolicy.displayMetadata(saltAnchor, song),
        )
        assertEquals(
            TimelineContentPolicy.DisplayMetadata("", ""),
            TimelineContentPolicy.displayMetadata(saltAnchor, null),
        )
    }

    @Test
    fun `clean anchors keep showing the session text`() {
        val anchor = content("Song", "Artist")

        assertEquals(
            TimelineContentPolicy.DisplayMetadata("Song", "Artist"),
            TimelineContentPolicy.displayMetadata(anchor, Song(name = "Localized", artist = "Localized Artist")),
        )
    }

    // ---- 污染确认前的头几秒：键不翻转、立即匹配 ----

    private val unflaggedSaltAnchor = saltAnchor.copy(title = "教佢揸车揾戏嚟做", polluted = false)

    @Test
    fun `anchor key ignores the title when duration and artist or album are known`() {
        val first = unflaggedSaltAnchor
        val second = unflaggedSaltAnchor.copy(title = "去咖啡湾撑艇")

        assertEquals(first.normalizedKey(), second.normalizedKey())
        // 污染确认后标题留空，键仍然不变，所以确认瞬间不会被当成换曲。
        assertEquals(first.normalizedKey(), saltAnchor.normalizedKey())
    }

    @Test
    fun `anchor key still tells different tracks apart`() {
        val base = unflaggedSaltAnchor
        val keys = setOf(
            base.normalizedKey(),
            base.copy(durationMs = 201_000L).normalizedKey(),
            base.copy(album = "别的专辑").normalizedKey(),
            base.copy(artist = "别人 - 别的歌").normalizedKey(),
            base.copy(packageName = "other.player").normalizedKey(),
        )

        assertEquals(5, keys.size)
    }

    @Test
    fun `anchor key keeps the title when duration is unknown or no other field discriminates`() {
        val noDuration = unflaggedSaltAnchor.copy(durationMs = 0L)
        assertFalse(noDuration.normalizedKey() == noDuration.copy(title = "去咖啡湾撑艇").normalizedKey())

        val titleOnly = TrackIdentity(packageName = SALT, title = "A", durationMs = 200_000L)
        assertFalse(titleOnly.normalizedKey() == titleOnly.copy(title = "B").normalizedKey())
    }

    @Test
    fun `media id keeps deciding the key`() {
        val withId = unflaggedSaltAnchor.copy(mediaId = "m1")

        assertEquals(withId.normalizedKey(), withId.copy(title = "x", artist = "y", durationMs = 1L).normalizedKey())
        assertFalse(withId.normalizedKey() == withId.copy(mediaId = "m2").normalizedKey())
    }

    // ---- 单曲中途从原始会话恢复出干净标题/歌手：键必须保持不变（2026-10-26 真机：230049 在此处丢歌词） ----

    private val tokenAnchor = unflaggedSaltAnchor.copy(trackToken = "7")

    private fun recoveredFrom(session: TrackIdentity, title: String, artist: String) =
        session.copy(title = title, artist = artist)

    @Test
    fun `recovering clean names mid-track does not change the anchor key`() {
        val recovered = recoveredFrom(tokenAnchor, "跟佢做个 FRIEND", "许冠杰")

        assertEquals(tokenAnchor.normalizedKey(), recovered.normalizedKey())
        assertEquals(tokenAnchor.copy(polluted = true, title = "").normalizedKey(), recovered.normalizedKey())
        // 没有编号时键读歌手，恢复出干净歌手就会翻转；编号正是为此而设。
        assertFalse(
            unflaggedSaltAnchor.normalizedKey() ==
                recoveredFrom(unflaggedSaltAnchor, "跟佢做个 FRIEND", "许冠杰").normalizedKey(),
        )
    }

    @Test
    fun `recovered anchors still tell different tracks apart`() {
        val recovered = recoveredFrom(tokenAnchor, "跟佢做个 FRIEND", "许冠杰")
        val keys = setOf(
            recovered.normalizedKey(),
            recovered.copy(trackToken = "8").normalizedKey(),
            recovered.copy(packageName = "other.player").normalizedKey(),
        )

        assertEquals(3, keys.size)
    }

    @Test
    fun `a recovered anchor matches the clean content for its own song only`() {
        val recovered = recoveredFrom(tokenAnchor, "跟佢做个 FRIEND", "许冠杰")

        assertTrue(content("跟佢做个 FRIEND", "许冠杰", album = "最佳拍档大显神通").matchesAvailableFields(recovered))
        assertFalse(content("另一首歌", "许冠杰", album = "最佳拍档大显神通").matchesAvailableFields(recovered))
    }

    // ---- 酷狗：曲首 1.5 秒内专辑补全、时长取整、歌手被拼成「歌手-歌名」，键必须保持不变 ----
    // 2026-10-26 真机（watch_kugou.txt）：01:59:12.086 起播 ALBUM="" DURATION=147382，
    // 12.099 DURATION=147000，12.330 ALBUM 补全，13.485 ARTIST 变成「The Walters-I Love You So (Acoustic)」。

    private val kugouStart = TrackIdentity(
        packageName = KUGOU,
        title = "I Love You So (Acoustic)",
        artist = "The Walters",
        album = "",
        durationMs = 147_382L,
        trackToken = "11",
    )

    @Test
    fun `kugou start-of-track mutations never change the token key`() {
        val rounded = kugouStart.copy(durationMs = 147_000L)
        val albumFilled = rounded.copy(album = "I Love You So (Acoustic)")
        val artistCombined = albumFilled.copy(
            title = "Lyrics by：Charles Ekhaus",
            artist = "The Walters-I Love You So (Acoustic)",
        )
        val unresolved = artistCombined.copy(title = "", polluted = true)

        val keys = listOf(kugouStart, rounded, albumFilled, artistCombined, unresolved).map { it.normalizedKey() }

        assertEquals(1, keys.toSet().size)
        // 同样的序列不带编号时，每一步都会翻转，这就是 230051 在酷狗上丢歌词的原因。
        val withoutToken = listOf(kugouStart, rounded, albumFilled, artistCombined)
            .map { it.copy(trackToken = null).normalizedKey() }
        assertEquals(4, withoutToken.toSet().size)
    }

    @Test
    fun `a new token is a new track even with identical session fields`() {
        assertFalse(kugouStart.normalizedKey() == kugouStart.copy(trackToken = "12").normalizedKey())
    }

    @Test
    fun `token key needs a known duration and an artist or album`() {
        // 时长未知或没有其他字段区分时，仍用含标题的组合键，不能只靠编号。
        val noDuration = kugouStart.copy(durationMs = 0L)
        assertFalse(noDuration.normalizedKey() == noDuration.copy(title = "x").normalizedKey())

        val titleOnly = TrackIdentity(packageName = KUGOU, title = "A", durationMs = 200_000L, trackToken = "11")
        assertFalse(titleOnly.normalizedKey() == titleOnly.copy(title = "B").normalizedKey())
    }

    @Test
    fun `media id still wins over the token`() {
        val withId = kugouStart.copy(mediaId = "m1")

        assertEquals(withId.normalizedKey(), withId.copy(trackToken = "99").normalizedKey())
        assertFalse(withId.normalizedKey() == withId.copy(mediaId = "m2").normalizedKey())
    }

    @Test
    fun `a session whose artist string is explained by the content matches before pollution is confirmed`() {
        val song = content("跟佢做个 FRIEND", "许冠杰")

        assertTrue(song.matchesAvailableFields(unflaggedSaltAnchor))
        assertTrue(unflaggedSaltAnchor.matchesAvailableFields(song))
        assertFalse(content("另一首歌", "许冠杰").matchesAvailableFields(unflaggedSaltAnchor))
    }

    @Test
    fun `clean sessions with different titles still do not match`() {
        val anchor = content("Song", "Artist")

        assertFalse(content("Other", "Artist").matchesAvailableFields(anchor))
    }

    @Test
    fun `session text is untrusted when polluted or explained by the matched song`() {
        val song = Song(name = "跟佢做个 FRIEND", artist = "许冠杰")

        assertTrue(TimelineContentPolicy.isSessionTextUntrusted(saltAnchor, null))
        assertTrue(TimelineContentPolicy.isSessionTextUntrusted(unflaggedSaltAnchor, song))
        assertFalse(TimelineContentPolicy.isSessionTextUntrusted(unflaggedSaltAnchor, null))
        assertFalse(TimelineContentPolicy.isSessionTextUntrusted(content("Song", "Artist"), song))
        assertEquals(
            TimelineContentPolicy.DisplayMetadata("跟佢做个 FRIEND", "许冠杰"),
            TimelineContentPolicy.displayMetadata(unflaggedSaltAnchor, song),
        )
    }

    private companion object {
        const val SALT = "com.salt.music"
        const val KUGOU = "com.kugou.android"
    }
}

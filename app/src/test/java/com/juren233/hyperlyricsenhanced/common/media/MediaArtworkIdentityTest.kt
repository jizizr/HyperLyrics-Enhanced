package com.juren233.hyperlyricsenhanced.common.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaArtworkIdentityTest {
    @Test
    fun cachedArtworkTitleMatchingIsStrictButWhitespaceAndCaseInsensitive() {
        assertEquals(
            true,
            MediaMetadataHelper.artworkTitlesMatch("  Firework ", "firework"),
        )
        assertEquals(
            false,
            MediaMetadataHelper.artworkTitlesMatch("Firework", "Teenage Dream"),
        )
        assertEquals(
            false,
            MediaMetadataHelper.artworkTitlesMatch("", "Firework"),
        )
    }


    @Test
    fun `media id is authoritative across metadata localization changes`() {
        val first = MediaMetadataHelper.buildArtworkCacheKey(
            packageName = "com.example.player",
            mediaId = "song-42",
            title = "Original title",
            artist = "Original artist",
            album = "Original album",
            duration = 180_000L,
        )
        val localized = MediaMetadataHelper.buildArtworkCacheKey(
            packageName = "com.example.player",
            mediaId = "song-42",
            title = "本地化标题",
            artist = "本地化歌手",
            album = "本地化专辑",
            duration = 180_000L,
        )

        assertEquals(first, localized)
    }

    @Test
    fun `stable album identity tolerates temporary title replacement`() {
        val track = MediaMetadataHelper.buildArtworkCacheKey(
            packageName = "com.example.player",
            mediaId = "",
            title = "Song title",
            artist = "Artist",
            album = "Album",
            duration = 201_234L,
        )
        val lyricLineTitle = MediaMetadataHelper.buildArtworkCacheKey(
            packageName = "com.example.player",
            mediaId = "",
            title = "Current lyric sentence",
            artist = "Artist",
            album = "Album",
            duration = 201_234L,
        )

        assertEquals(track, lyricLineTitle)
    }

    @Test
    fun `different packages and durations remain isolated`() {
        val first = MediaMetadataHelper.buildArtworkCacheKey(
            packageName = "com.example.player.one",
            mediaId = "",
            title = "Song",
            artist = "Artist",
            album = "Album",
            duration = 180_000L,
        )
        val otherPackage = MediaMetadataHelper.buildArtworkCacheKey(
            packageName = "com.example.player.two",
            mediaId = "",
            title = "Song",
            artist = "Artist",
            album = "Album",
            duration = 180_000L,
        )
        val otherDuration = MediaMetadataHelper.buildArtworkCacheKey(
            packageName = "com.example.player.one",
            mediaId = "",
            title = "Song",
            artist = "Artist",
            album = "Album",
            duration = 181_000L,
        )

        assertNotEquals(first, otherPackage)
        assertNotEquals(first, otherDuration)
    }

    @Test
    fun `missing identity does not create a reusable artwork key`() {
        assertNull(
            MediaMetadataHelper.buildArtworkCacheKey(
                packageName = "com.example.player",
                mediaId = "",
                title = "",
                artist = "",
                album = "Album only",
                duration = 180_000L,
            )
        )
    }

    @Test
    fun `lyric line title rewrites keep the track identity stable`() {
        fun signature(title: String, mediaId: String = "song-42") =
            MediaMetadataHelper.trackIdentitySignature(
                packageName = "com.example.player",
                mediaId = mediaId,
                title = title,
                artist = "Artist",
                album = "Album",
                duration = 180_000L,
                hasDirectArtwork = false,
            )
        val first = signature("First lyric line")
        assertEquals(first, signature("Second lyric line"))
        assertNotEquals(first, signature("First lyric line", mediaId = "song-43"))

        val albumIdentity = MediaMetadataHelper.trackIdentitySignature(
            packageName = "com.example.player",
            mediaId = "",
            title = "First lyric line",
            artist = "Artist",
            album = "Album",
            duration = 180_000L,
            hasDirectArtwork = false,
        )
        assertEquals(
            albumIdentity,
            MediaMetadataHelper.trackIdentitySignature(
                packageName = "com.example.player",
                mediaId = "",
                title = "Second lyric line",
                artist = "Artist",
                album = "Album",
                duration = 180_000L,
                hasDirectArtwork = false,
            ),
        )
    }

    @Test
    fun `track identity changes when artist album duration or artwork arrives`() {
        fun signature(
            artist: String = "Artist",
            album: String = "Album",
            duration: Long = 180_000L,
            hasDirectArtwork: Boolean = false,
        ) = MediaMetadataHelper.trackIdentitySignature(
            packageName = "com.example.player",
            mediaId = "",
            title = "Song",
            artist = artist,
            album = album,
            duration = duration,
            hasDirectArtwork = hasDirectArtwork,
        )
        val base = signature()
        assertNotEquals(base, signature(artist = "Another artist"))
        assertNotEquals(base, signature(album = "Another album"))
        assertNotEquals(base, signature(duration = 181_000L))
        assertNotEquals(base, signature(hasDirectArtwork = true))
    }

    @Test
    fun `missing media identity yields no signature`() {
        assertNull(
            MediaMetadataHelper.trackIdentitySignature(
                packageName = "com.example.player",
                mediaId = "",
                title = "",
                artist = "",
                album = "Album only",
                duration = 180_000L,
                hasDirectArtwork = true,
            )
        )
    }
}

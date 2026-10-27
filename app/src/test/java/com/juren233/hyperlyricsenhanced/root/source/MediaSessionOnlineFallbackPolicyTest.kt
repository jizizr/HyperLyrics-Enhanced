package com.juren233.hyperlyricsenhanced.root.source

import com.juren233.hyperlyricsenhanced.common.MediaSessionOnlineFallbackPreferences
import com.juren233.hyperlyricsenhanced.common.MediaSessionOnlineFallbackPreferences.Settings
import com.juren233.hyperlyricsenhanced.timeline.model.TrackIdentity
import org.junit.Assert.*
import org.junit.Test

class MediaSessionOnlineFallbackPolicyTest {
    private val track = TrackIdentity("org.chromium.webapk.example", title = "Song", artist = "Artist", durationMs = 180_000)
    private val settings = Settings(enabled = true, packages = setOf(track.packageName))

    @Test fun `complete metadata and exact configured package are eligible`() {
        assertTrue(MediaSessionOnlineFallbackPolicy.eligible(settings, track, true))
        val chrome = track.copy(packageName = "com.android.chrome")
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings, chrome, true))
        assertTrue(MediaSessionOnlineFallbackPolicy.eligible(settings.copy(packages = setOf(chrome.packageName)), chrome, true))
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings, track.copy(packageName = "org.chromium.webapk.other"), true))
    }

    @Test fun `disabled feature empty allowlist and invalid sessions are ineligible`() {
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(Settings(), track, true))
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings.copy(enabled = false), track, true))
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings.copy(packages = emptySet()), track, true))
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings, track, false))
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings, null, true))
    }

    @Test fun `placeholder and unrecovered polluted metadata are ineligible`() {
        for (title in listOf("", " ", "Playing~", "Unknown", "Unknown Song", "未知歌曲", "Loading...", "Playing…", "---")) {
            assertFalse(title, MediaSessionOnlineFallbackPolicy.eligible(settings, track.copy(title = title), true))
        }
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings, track.copy(artist = "Unknown artist"), true))
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings, track.copy(durationMs = 0), true))
        assertFalse(MediaSessionOnlineFallbackPolicy.eligible(settings, track.copy(polluted = true), true))
    }

    @Test fun `package editor accepts exact packages only`() {
        assertEquals(setOf("com.android.chrome", track.packageName), MediaSessionOnlineFallbackPreferences.parsePackages(
            "com.android.chrome\n${track.packageName}, com.android.chrome org.chromium.webapk.* invalid"
        ))
    }

    @Test fun `reused browser media id cannot hide a changed song`() {
        val identified = track.copy(mediaId = "fixed-id")
        assertFalse(MediaSessionOnlineFallbackPolicy.sameLookupTrack(identified, identified.copy(title = "Other")))
        assertTrue(MediaSessionOnlineFallbackPolicy.sameLookupTrack(identified, identified.copy(album = "New album", durationMs = 180_001)))
    }
}

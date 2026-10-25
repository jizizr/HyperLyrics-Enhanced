/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.media

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderNextTrackFrame
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPreferencePolicy
import java.util.Locale

internal data class NextTrackMetadata(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/**
 * 查找下一首时对「当前曲」的一种称呼。同一首歌可以有几种：会话里的原始文本，
 * 以及歌词侧（Provider 发布、不受会话改写影响）的歌名/歌手。
 */
internal data class CurrentTrackKey(
    val id: String?,
    val title: String,
    val artist: String,
)

internal class NextTrackMetadataStore(
    private val nowMs: () -> Long,
    private val ttlMs: Long,
) {
    private data class CacheKey(val playerPackageName: String, val identity: String)

    private data class Entry(
        val aliases: Set<String>,
        val metadata: NextTrackMetadata,
        val updatedAtMs: Long,
    )

    private val entries = mutableMapOf<CacheKey, Entry>()

    @Synchronized
    fun update(
        playerPackageName: String,
        currentId: String,
        currentTitle: String,
        currentArtist: String,
        metadata: NextTrackMetadata,
    ): Boolean {
        pruneExpired()
        val aliases = identityAliases(currentId, currentTitle, currentArtist)
        if (playerPackageName.isBlank() || aliases.isEmpty() || metadata.title.isBlank()) return false
        val entry = Entry(aliases, metadata, nowMs())
        aliases.forEach { alias -> entries[CacheKey(playerPackageName, alias)] = entry }
        return true
    }

    @Synchronized
    fun clear(
        playerPackageName: String,
        currentId: String,
        currentTitle: String,
        currentArtist: String,
    ) {
        pruneExpired()
        val aliases = identityAliases(currentId, currentTitle, currentArtist)
        if (aliases.isEmpty()) {
            entries.keys.removeAll { it.playerPackageName == playerPackageName }
            return
        }
        val matchingEntries = aliases.mapNotNull { entries[CacheKey(playerPackageName, it)] }.toSet()
        matchingEntries.forEach { entry ->
            entry.aliases.forEach { alias -> entries.remove(CacheKey(playerPackageName, alias)) }
        }
    }

    @Synchronized
    fun clearPlayers(playerPackageNames: Set<String>) {
        if (playerPackageNames.isEmpty()) return
        entries.keys.removeAll { it.playerPackageName in playerPackageNames }
    }

    fun find(
        playerPackageName: String,
        currentId: String?,
        currentTitle: String,
        currentArtist: String,
    ): NextTrackMetadata? = findFirst(
        playerPackageName,
        listOf(CurrentTrackKey(currentId, currentTitle, currentArtist)),
    )

    /** 按 [currentTracks] 的顺序逐个称呼查找，返回第一个命中的下一首。 */
    @Synchronized
    fun findFirst(
        playerPackageName: String,
        currentTracks: List<CurrentTrackKey>,
    ): NextTrackMetadata? {
        pruneExpired()
        return currentTracks.firstNotNullOfOrNull { current ->
            identityAliases(current.id.orEmpty(), current.title, current.artist)
                .firstNotNullOfOrNull { alias ->
                    entries[CacheKey(playerPackageName, alias)]?.metadata
                }
        }
    }

    private fun pruneExpired() {
        val now = nowMs()
        entries.entries.removeAll { (_, entry) -> now - entry.updatedAtMs > ttlMs }
    }

    private fun identityAliases(id: String, title: String, artist: String): Set<String> = buildSet {
        id.trim().takeIf(String::isNotEmpty)?.let { add("id:$it") }
        val normalizedTitle = normalizeText(title)
        if (normalizedTitle.isNotEmpty()) {
            add("text:$normalizedTitle|${normalizeText(artist)}")
        }
    }

    private fun normalizeText(value: String): String = value
        .trim()
        .replace(WHITESPACE, " ")
        .lowercase(Locale.ROOT)

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

internal object NextTrackMetadataCache {
    enum class ControlResult {
        UPDATED,
        CLEARED,
        REJECTED_PROVIDER,
        REJECTED_FRAME,
    }

    private const val CACHE_TTL_MS = 15_000L
    private val store = NextTrackMetadataStore(SystemClock::elapsedRealtime, CACHE_TTL_MS)

    fun accept(
        providerPackageName: String,
        playerPackageName: String,
        frame: OfficialProviderNextTrackFrame,
    ): ControlResult {
        if (!isProviderAccepted(
                providerPackageName = providerPackageName,
                playerPackageName = playerPackageName,
                officialProviderPreference =
                    OfficialProviderPreferencePolicy.preferenceState(playerPackageName),
            )
        ) {
            return ControlResult.REJECTED_PROVIDER
        }
        if (frame.clear) {
            store.clear(
                playerPackageName,
                frame.currentId,
                frame.currentTitle,
                frame.currentArtist,
            )
            return ControlResult.CLEARED
        }
        val updated = store.update(
            playerPackageName = playerPackageName,
            currentId = frame.currentId,
            currentTitle = frame.currentTitle,
            currentArtist = frame.currentArtist,
            metadata = NextTrackMetadata(
                id = frame.nextId,
                title = frame.nextTitle.trim(),
                artist = frame.nextArtist.trim(),
                album = frame.nextAlbum.trim(),
                durationMs = frame.nextDurationMs,
            ),
        )
        return if (updated) ControlResult.UPDATED else ControlResult.REJECTED_FRAME
    }

    fun find(
        playerPackageName: String,
        currentTracks: List<CurrentTrackKey>,
    ): NextTrackMetadata? = store.findFirst(playerPackageName, currentTracks)

    /**
     * 会话文本（[currentTitle]/[currentArtist]）排在前面；歌词侧歌名/歌手非空时作为第二个称呼。
     * 车载歌词改写过的会话里，会话文本是歌词行或「歌手-歌名」，而控制帧用的是 Provider 还原后的
     * 歌名/歌手，只有歌词侧的称呼能对上。歌词侧文本必须来自同一个播放器包，由调用方保证。
     */
    fun currentTrackKeys(
        currentId: String?,
        currentTitle: String,
        currentArtist: String,
        lyricTitle: String?,
        lyricArtist: String?,
    ): List<CurrentTrackKey> {
        val session = CurrentTrackKey(currentId, currentTitle, currentArtist)
        val title = lyricTitle?.trim().orEmpty()
        if (title.isEmpty()) return listOf(session)
        val lyric = CurrentTrackKey(currentId, title, lyricArtist?.trim().orEmpty())
        return listOf(session, lyric).distinct()
    }

    fun clearPlayers(playerPackageNames: Set<String>) {
        store.clearPlayers(playerPackageNames)
    }

    internal fun isProviderAccepted(
        providerPackageName: String,
        playerPackageName: String,
        officialProviderPreference: Boolean?,
    ): Boolean =
        (
            OfficialProviderCatalog.isOfficialProviderPair(providerPackageName, playerPackageName) &&
                officialProviderPreference != false
            ) || (
                providerPackageName == OfficialProviderCatalog.CORE_PACKAGE_NAME &&
                    playerPackageName == OfficialProviderCatalog.SALT_PLAYER_PACKAGE_NAME
                )
}

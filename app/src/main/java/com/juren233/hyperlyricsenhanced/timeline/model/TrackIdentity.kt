/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.timeline.model

import com.juren233.hyperlyricsenhanced.common.media.MediaSessionMetadataSanitizer
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * 时间轴快照的曲目身份。
 *
 * 生产者（模块应用进程）与消费者（SystemUI）各自从媒体会话元数据构建本对象；
 * [matches] 是两侧唯一的身份判定入口。拼接键规则与应用进程既有
 * [com.juren233.hyperlyricsenhanced.service.source.MediaSongIdentity] 保持一致
 * （trim 后以 \u001F 连接），保证两侧对同一首歌得到相同键。
 *
 * 任何模糊匹配（标题相似、时长接近）都禁止进入身份判定；模糊候选只能用于 shadow 对比。
 *
 * [polluted] 为 true 表示会话被车载歌词改写且真实标题/歌手无法确定：[title] 留空，
 * [artist] 保存会话里单曲内稳定的原始歌手串，仅用于身份键与匹配，**不得用于展示**
 * （展示用 [displayTitle]/[displayArtist]，应用歌词内容时改用已匹配歌曲的名称与歌手）。
 *
 * [trackToken] 是净化器给当前曲目段的编号，只在净化器判定换曲时才变。会话里的歌手、专辑、
 * 时长在曲首都可能被播放器改动（酷狗：专辑从空串补全、时长取整、歌手被拼成「歌手-歌名」），
 * 恢复出干净名称又会再改一次 [artist]；任何由这些字段拼出的键都会把同一首歌当成换曲。
 * 没有 mediaId 的会话因此用编号作键，null 表示没有经过净化器。
 */
@Serializable
data class TrackIdentity(
    val packageName: String,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val polluted: Boolean = false,
    val trackToken: String? = null,
) {
    /** 可展示的标题：被污染且无法恢复时为空，而不是歌词行。 */
    val displayTitle: String get() = if (polluted) "" else title

    /** 可展示的歌手：被污染时 [artist] 是原始组合串，不能展示。 */
    val displayArtist: String get() = if (polluted) "" else artist

    private fun normalizedParts(): List<String> = listOf(
        packageName.trim(),
        title.trim(),
        artist.trim(),
        album.trim(),
        durationMs.toString(),
    )

    /**
     * 规范化组合键（不含 mediaId）：与应用进程 MediaSongIdentity 规则逐字段一致。
     */
    private fun compositeKey(): String = normalizedParts().joinToString(SEPARATOR.toString())

    /** QQ HD 的 MediaSession ID 是会话序号，不能作为切歌身份。 */
    private fun qqMusicHdKey(): String? {
        if (packageName.trim() != QQ_MUSIC_HD_PACKAGE) return null
        return listOf(packageName.trim(), qqMusicHdText(title), qqMusicHdText(artist))
            .joinToString(SEPARATOR.toString())
    }

    private fun qqMusicHdText(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    /**
     * 规范化键（锚点侧的换曲判据，不参与跨进程严格匹配）。QQ HD 使用歌名和歌手，其余播放器
     * 优先使用 mediaId。
     *
     * 没有 mediaId 时，只要时长已知且歌手或专辑非空，键就不含标题：时长精确到毫秒，再叠加歌手与
     * 专辑，足以区分曲目；而标题是会话里最容易变的字段（车载歌词逐句改写、占位标题更新），
     * 让它进入键会使同一首歌的每次标题变化都被当成换曲、清空时间轴。时长未知时无法区分，
     * 仍使用含标题的组合键。
     *
     * 同样条件下若有 [trackToken]，键就是包名加编号，不再读歌手、专辑和时长：这三个字段在曲首
     * 都可能被改动，见类说明。编号的换曲判定沿用同样的信号（时长容差、专辑、进度归零），
     * 只是对曲首补全与改写不敏感。
     */
    fun normalizedKey(): String {
        qqMusicHdKey()?.let { return it }
        val id = mediaId?.trim().orEmpty()
        val token = trackToken?.trim().orEmpty()
        val hasKeyFields = durationMs > 0L && (artist.isNotBlank() || album.isNotBlank())
        return when {
            id.isNotEmpty() -> listOf(packageName.trim(), id).joinToString(SEPARATOR.toString())
            hasKeyFields && token.isNotEmpty() ->
                listOf(packageName.trim(), token).joinToString(SEPARATOR.toString())
            hasKeyFields ->
                listOf(packageName.trim(), artist.trim(), album.trim(), durationMs.toString())
                    .joinToString(SEPARATOR.toString())
            else -> compositeKey()
        }
    }

    /**
     * 严格身份匹配：
     * - 两侧都有非空 mediaId → 包名 + mediaId 必须相等，且不再看其他字段；
     * - 任一侧缺 mediaId → 双方组合键必须完全相等（此时 mediaId 差异不参与判定，
     *   因此这里必须用组合键而不是 [normalizedKey]，后者会被自身的 mediaId 短路）。
     */
    fun matches(other: TrackIdentity): Boolean {
        if (packageName.trim() == QQ_MUSIC_HD_PACKAGE ||
            other.packageName.trim() == QQ_MUSIC_HD_PACKAGE
        ) {
            return qqMusicHdKey() != null && qqMusicHdKey() == other.qqMusicHdKey()
        }
        val selfId = mediaId?.trim().orEmpty()
        val otherId = other.mediaId?.trim().orEmpty()
        if (selfId.isNotEmpty() && otherId.isNotEmpty()) {
            return packageName.trim() == other.packageName.trim() && selfId == otherId
        }
        return compositeKey() == other.compositeKey()
    }

    /**
     * SystemUI 进程内来源的确定性部分匹配。只比较双方都提供的字段，不做相似度、时长
     * 容差或简繁转换；两侧都有 mediaId 时仍以包名 + mediaId 为唯一判据。
     */
    fun matchesAvailableFields(other: TrackIdentity): Boolean {
        val selfPackage = packageName.trim()
        val otherPackage = other.packageName.trim()
        if (selfPackage.isNotEmpty() && otherPackage.isNotEmpty() && selfPackage != otherPackage) {
            return false
        }

        if (selfPackage == QQ_MUSIC_HD_PACKAGE || otherPackage == QQ_MUSIC_HD_PACKAGE) {
            val selfTitle = qqMusicHdText(title)
            val otherTitle = qqMusicHdText(other.title)
            val selfArtist = qqMusicHdText(artist)
            val otherArtist = other.qqMusicHdText(other.artist)
            return selfPackage == otherPackage &&
                selfTitle.isNotEmpty() && otherTitle.isNotEmpty() &&
                selfArtist.isNotEmpty() && otherArtist.isNotEmpty() &&
                selfTitle == otherTitle && selfArtist == otherArtist
        }

        val selfId = mediaId?.trim().orEmpty()
        val otherId = other.mediaId?.trim().orEmpty()
        if (selfId.isNotEmpty() && otherId.isNotEmpty()) return selfId == otherId

        // 一侧的歌手串恰好能由另一侧的歌名+歌手拼出，说明它来自被改写的会话，
        // 不必等净化器累计到足够的漂移证据，标题同样不可信。
        val untrusted = when {
            polluted -> this
            other.polluted -> other
            MediaSessionMetadataSanitizer.combinedExplains(artist, other.title, other.artist) -> this
            MediaSessionMetadataSanitizer.combinedExplains(other.artist, title, artist) -> other
            else -> null
        }
        if (untrusted != null) {
            return matchesUntrustedFields(untrusted, if (untrusted === this) other else this)
        }

        val selfTitle = title.trim()
        val otherTitle = other.title.trim()
        if (selfTitle.isNotEmpty() && otherTitle.isNotEmpty() && selfTitle != otherTitle) return false

        val selfArtist = artist.trim()
        val otherArtist = other.artist.trim()
        if (selfArtist.isNotEmpty() && otherArtist.isNotEmpty() && selfArtist != otherArtist) return false

        val selfAlbum = album.trim()
        val otherAlbum = other.album.trim()
        if (selfAlbum.isNotEmpty() && otherAlbum.isNotEmpty() && selfAlbum != otherAlbum) return false

        return selfId.isNotEmpty() || otherId.isNotEmpty() ||
            (selfTitle.isNotEmpty() && otherTitle.isNotEmpty())
    }

    /**
     * 一侧来自被车载歌词改写的会话（[polluted]，或歌手串能被另一侧拼出）：标题不可信，不参与比较。已匹配的歌曲必须能拼出会话里
     * 稳定的原始歌手串（歌名与歌手任意顺序、任意分隔符），这是只有同一首歌才会满足的条件。
     * 原始歌手串本身是干净歌手时（只有标题被改写）无法区分同歌手的不同歌，
     * 额外要求专辑相同，或时长都已知且相差不超过 [ARTIST_ONLY_DURATION_TOLERANCE_MS]。
     */
    private fun matchesUntrustedFields(untrusted: TrackIdentity, trusted: TrackIdentity): Boolean {
        val untrustedAlbum = untrusted.album.trim()
        val trustedAlbum = trusted.album.trim()
        val albumsKnownEqual = untrustedAlbum.isNotEmpty() && trustedAlbum.isNotEmpty() &&
            untrustedAlbum.equals(trustedAlbum, ignoreCase = true)
        if (untrustedAlbum.isNotEmpty() && trustedAlbum.isNotEmpty() && !albumsKnownEqual) return false

        val rawArtist = untrusted.artist.trim()
        if (rawArtist.isEmpty()) return false
        if (trusted.polluted) return rawArtist.equals(trusted.artist.trim(), ignoreCase = true)

        if (MediaSessionMetadataSanitizer.combinedExplains(rawArtist, trusted.title, trusted.artist)) return true

        val sameArtistOnly = MediaSessionMetadataSanitizer.normalizeForMatch(rawArtist) ==
            MediaSessionMetadataSanitizer.normalizeForMatch(trusted.artist)
        if (!sameArtistOnly) return false
        val durationsKnownClose = untrusted.durationMs > 0L && trusted.durationMs > 0L &&
            kotlin.math.abs(untrusted.durationMs - trusted.durationMs) <= ARTIST_ONLY_DURATION_TOLERANCE_MS
        return albumsKnownEqual || durationsKnownClose
    }

    companion object {
        private const val ARTIST_ONLY_DURATION_TOLERANCE_MS = 5_000L
        private const val SEPARATOR = '\u001F'
        private const val QQ_MUSIC_HD_PACKAGE = "com.tencent.qqmusicpad"
    }
}

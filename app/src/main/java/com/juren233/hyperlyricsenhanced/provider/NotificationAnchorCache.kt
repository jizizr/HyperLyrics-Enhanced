/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import com.juren233.hyperlyricsenhanced.common.media.MediaSessionMetadataSanitizer
import com.juren233.hyperlyricsenhanced.common.media.TrackAnchor

/**
 * 播放器进程内通知锚点的曲目段缓存。读取通知是一次跨进程调用，车载歌词开启后会话标题逐句变化，
 * 不缓存时每句歌词都要查一次。
 *
 * 只有能代表本曲目段的结果才缓存，其余情况每次照常查询，与不缓存时的判定一致：
 * - 曲目段内首次查询后 [SETTLE_MS] 内不缓存：播放器先发元数据、稍后才更新通知，
 *   这段时间读到的可能还是上一首的通知；
 * - 空结果不缓存：通知可能晚于元数据发布；
 * - 标题等于当前会话标题的结果不缓存：通知可能由同一份会话元数据生成，此时它会随会话一起漂移，
 *   缓存某一刻的值会把歌词行当作真实歌名；
 * - 缓存最长保留 [MAX_AGE_MS]，限制曲目段内通知合法变化（如原名恢复）后旧值的存活时间。
 *
 * 不加锁：调用方在净化器锁内调用（见 [MediaSessionMetadataSanitizer.sanitize]）。
 */
internal class NotificationAnchorCache<K : Any>(
    private val query: (sessionKey: K) -> List<TrackAnchor>,
) {
    private var segment: Segment<K>? = null

    /**
     * @param sessionKey 本次会话的标识（MediaSession.Token），换会话时不沿用缓存。
     * @param trackToken 净化器给出的曲目段编号。
     * @param rawTitle 本次采样的原始会话标题。
     * @param nowMs 单调时钟毫秒。
     */
    fun anchors(sessionKey: K, trackToken: String, rawTitle: String?, nowMs: Long): List<TrackAnchor> {
        val current = segment
            ?.takeIf { it.sessionKey == sessionKey && it.trackToken == trackToken }
            ?: Segment(sessionKey, trackToken, firstQueryAtMs = nowMs).also { segment = it }
        current.cached?.let { cached ->
            if (nowMs - current.cachedAtMs < MAX_AGE_MS) return cached
        }
        val anchors = query(sessionKey)
        val titleNorm = MediaSessionMetadataSanitizer.normalizeForMatch(rawTitle)
        val cacheable = nowMs - current.firstQueryAtMs >= SETTLE_MS &&
            anchors.isNotEmpty() &&
            anchors.none { MediaSessionMetadataSanitizer.normalizeForMatch(it.title) == titleNorm }
        current.cached = anchors.takeIf { cacheable }
        current.cachedAtMs = nowMs
        return anchors
    }

    private class Segment<K>(
        val sessionKey: K,
        val trackToken: String,
        val firstQueryAtMs: Long,
    ) {
        var cached: List<TrackAnchor>? = null
        var cachedAtMs: Long = 0L
    }

    companion object {
        const val SETTLE_MS = 1_000L
        const val MAX_AGE_MS = 30_000L
    }
}

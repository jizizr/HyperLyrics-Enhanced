/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.juren233.hyperlyricsenhanced.common.media.AnchorSource
import com.juren233.hyperlyricsenhanced.common.media.MediaSessionMetadataAdapter
import com.juren233.hyperlyricsenhanced.common.media.MediaSessionMetadataSanitizer
import com.juren233.hyperlyricsenhanced.common.media.SanitizedTrack
import com.juren233.hyperlyricsenhanced.common.media.SessionMetadataSample
import com.juren233.hyperlyricsenhanced.common.media.TrackAnchor

/**
 * 宿主交给 Provider 的 MediaMetadata 的唯一出口过滤器。
 *
 * 国内音乐 App 的车载歌词（蓝牙歌词）会把会话标题改成歌词行、歌手改成「歌名-歌手」。
 * Provider 用这些字段找歌词，读到被污染的值就找不到歌或发布错误的歌名。宿主在交付前
 * 统一净化：已恢复时交付改写后的副本，其余情况交付原对象，播放器自己的会话不受影响，
 * 因此所有 Provider（含旧版本）无需修改即可得到干净的元数据。
 *
 * @param notificationAnchors 是否把播放器自己的媒体通知标题当作独立锚点。只在播放器进程内
 *   有意义：应用只能读取自己发布的通知，SystemUI 进程里读到的不是播放器的通知。
 */
internal class OfficialProviderMetadataFeed(
    private val playerPackageName: String,
    private val applicationProvider: () -> Application?,
    private val notificationAnchors: Boolean,
    private val diagnostic: ((String) -> Unit)? = null,
) {
    private val sanitizer = MediaSessionMetadataSanitizer()
    private val notificationAnchorCache = NotificationAnchorCache(::queryNotificationAnchors)
    private var lastDiagnosticKey: String? = null

    fun filter(
        metadata: MediaMetadata?,
        playbackState: PlaybackState?,
        sessionToken: MediaSession.Token?,
    ): MediaMetadata? {
        metadata ?: return null
        return runCatching {
            val sample = MediaSessionMetadataAdapter.sampleOf(playerPackageName, metadata, playbackState)
            val track = sanitizer.sanitize(sample) { trackToken ->
                notificationAnchorsFor(sessionToken, trackToken, sample)
            }
            record(sample.title, track)
            if (track.status == SanitizedTrack.Status.RECOVERED) {
                MediaSessionMetadataAdapter.rewrite(metadata, track)
            } else {
                metadata
            }
        }.getOrElse { error ->
            diagnostic?.invoke("[CarLyricsMetadata] sanitize_failed package=$playerPackageName error=${error.message}")
            metadata
        }
    }

    private fun notificationAnchorsFor(
        token: MediaSession.Token?,
        trackToken: String,
        sample: SessionMetadataSample,
    ): List<TrackAnchor> {
        if (!notificationAnchors || token == null) return emptyList()
        return notificationAnchorCache.anchors(token, trackToken, sample.title, sample.sampledAtMs)
    }

    private fun queryNotificationAnchors(token: MediaSession.Token): List<TrackAnchor> {
        val application = applicationProvider() ?: return emptyList()
        val manager = application.getSystemService(NotificationManager::class.java) ?: return emptyList()
        return manager.activeNotifications.mapNotNull { sbn ->
            val extras = sbn.notification.extras ?: return@mapNotNull null
            @Suppress("DEPRECATION")
            val notificationToken = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            if (notificationToken != token) return@mapNotNull null
            extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()
                ?.takeIf(String::isNotBlank)
                ?.let { TrackAnchor(AnchorSource.NOTIFICATION, title = it) }
        }
    }

    /** 仅 debug：状态或来源变化时记录一次，不随每句歌词刷屏。 */
    private fun record(rawTitle: String?, track: SanitizedTrack) {
        val log = diagnostic ?: return
        val key = "${track.status}/${track.resolvedBy}"
        if (key == lastDiagnosticKey) return
        lastDiagnosticKey = key
        log(
            "[CarLyricsMetadata] package=$playerPackageName status=${track.status} " +
                "resolvedBy=${track.resolvedBy} title=${track.title} artist=${track.artist} " +
                "rawTitle=$rawTitle candidates=${track.candidates.size}",
        )
    }
}

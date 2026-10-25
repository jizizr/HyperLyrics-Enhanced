/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common.media

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog

/**
 * [MediaSessionMetadataSanitizer] 与 Android 类型之间的唯一转换点。
 * 宿主所有读取 MediaSession 标题/歌手的位置都经由这里，避免各处各写一套采样与改写规则。
 */
internal object MediaSessionMetadataAdapter {
    /**
     * 进程内唯一的净化器：身份构建与媒体信息读取共用同一份状态。各自累计标题漂移会得到不一致的判定，
     * 同一个标题变化也会被重复计数。
     */
    private val processSanitizer = MediaSessionMetadataSanitizer()

    /**
     * 对当前会话取一次进程内判定；null 表示该包不参与净化，调用方沿用原始文本。
     * Apple Music 的会话标题由模块自己改写（原名恢复会让同一首歌的标题多次变化），
     * 这类变化不是车载歌词的漂移证据。净化器失败也不能影响调用方。
     */
    fun processVerdict(
        packageName: String,
        metadata: MediaMetadata,
        playbackState: PlaybackState?,
    ): SanitizedTrack? {
        if (packageName == OfficialProviderCatalog.APPLE_MUSIC_PACKAGE_NAME) return null
        return runCatching {
            processSanitizer.sanitize(sampleOf(packageName, metadata, playbackState))
        }.getOrNull()
    }

    fun resetProcessState() = processSanitizer.reset()

    fun sampleOf(
        packageName: String,
        metadata: MediaMetadata,
        playbackState: PlaybackState?,
    ): SessionMetadataSample = SessionMetadataSample(
        packageName = packageName,
        mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
        artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
        album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
        albumArtist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
        durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
        positionMs = MediaMetadataHelper.estimatePlaybackPosition(playbackState),
        playing = playbackState?.state == PlaybackState.STATE_PLAYING,
        sampledAtMs = SystemClock.elapsedRealtime(),
    )

    /**
     * 只有标题与歌手都已恢复时才改写；无法确定的字段不猜，调用方应继续使用原始对象。
     * 改写的是副本，不会触碰播放器自己的 MediaSession，车载歌词因此不受影响。
     */
    fun rewrite(metadata: MediaMetadata, track: SanitizedTrack): MediaMetadata {
        val title = track.title ?: return metadata
        val builder = MediaMetadata.Builder(metadata)
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
        track.artist?.let { builder.putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
        // DISPLAY_* 存在时优先于 TITLE/ARTIST 生成 description，必须一并替换。
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)) {
            builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)) {
            track.artist?.let { builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it) }
        }
        return builder.build()
    }
}

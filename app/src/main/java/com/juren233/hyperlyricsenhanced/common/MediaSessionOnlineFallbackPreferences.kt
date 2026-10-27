package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.model.Source

/** 独立于歌词源选择；开启后自动为活动媒体会话补充歌词。 */
internal object MediaSessionOnlineFallbackPreferences {
    const val ENABLED = "key_hook_media_session_online_fallback_enabled"

    data class Settings(
        val enabled: Boolean = false,
        val sources: List<Source> = emptyList(),
    )

    fun read(prefs: SharedPreferences): Settings = Settings(
        enabled = prefs.getBoolean(ENABLED, false),
        sources = OnlineTranslationSourcePreferences.orderedSources(prefs),
    )

    fun isPreference(key: String?): Boolean = key == ENABLED
}

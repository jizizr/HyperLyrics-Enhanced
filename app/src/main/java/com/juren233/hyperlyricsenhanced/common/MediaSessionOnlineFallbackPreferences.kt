package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences
import com.juren233.hyperlyricsenhanced.online.model.Source

/** 独立于歌词源选择；仅授权精确列出的 MediaSession 包名。 */
internal object MediaSessionOnlineFallbackPreferences {
    const val ENABLED = "key_hook_media_session_online_fallback_enabled"
    const val PACKAGES = "key_hook_media_session_online_fallback_packages"

    data class Settings(
        val enabled: Boolean = false,
        val packages: Set<String> = emptySet(),
        val sources: List<Source> = emptyList(),
    )

    fun read(prefs: SharedPreferences): Settings = Settings(
        enabled = prefs.getBoolean(ENABLED, false),
        packages = parsePackages(prefs.getString(PACKAGES, "").orEmpty()),
        sources = OnlineTranslationSourcePreferences.orderedSources(prefs),
    )

    fun parsePackages(raw: String): Set<String> = raw.split(Regex("[\\s,;]+"))
        .filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) }
        .toSet()

    fun isPreference(key: String?): Boolean = key == ENABLED || key == PACKAGES
}

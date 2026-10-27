package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.MediaSessionOnlineFallbackPreferences
import com.juren233.hyperlyricsenhanced.common.PrefsBridge
import com.juren233.hyperlyricsenhanced.ui.navigation.LocalNavigator
import com.juren233.hyperlyricsenhanced.ui.navigation.Route
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun MediaSessionOnlineFallbackSettings() {
    val prefs = rememberHookPrefs()
    val save = rememberHookConfigSaver(prefs)
    val navigator = LocalNavigator.current
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(MediaSessionOnlineFallbackPreferences.ENABLED, false))
    }
    var packages by remember { mutableStateOf(MediaSessionOnlineFallbackPreferences.read(prefs).packages) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == MediaSessionOnlineFallbackPreferences.PACKAGES) {
                packages = MediaSessionOnlineFallbackPreferences.read(prefs).packages
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
        SwitchPreference(
            title = stringResource(R.string.media_session_fallback_title),
            summary = stringResource(R.string.media_session_fallback_summary),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                save(MediaSessionOnlineFallbackPreferences.ENABLED, it)
            },
        )
        ArrowPreference(
            title = stringResource(R.string.media_session_fallback_players),
            summary = stringResource(R.string.media_session_fallback_players_count, packages.size),
            onClick = { navigator.navigate(Route.MediaSessionOnlineFallbackPlayers) },
        )
    }
}

@Composable
internal fun MediaSessionOnlineFallbackPlayersPage() {
    val prefs = rememberHookPrefs()
    var packages by remember { mutableStateOf(MediaSessionOnlineFallbackPreferences.read(prefs).packages) }
    InstalledAppSelectionPage(
        title = stringResource(R.string.media_session_fallback_players),
        subtitle = stringResource(R.string.media_session_fallback_players_help),
        enabledPackages = packages,
        includeNonLauncherApps = true,
        showPackageNames = true,
        onCheckedChange = { packageName, checked ->
            packages = if (checked) packages + packageName else packages - packageName
            prefs.edit { putStringSet(MediaSessionOnlineFallbackPreferences.PACKAGES, packages) }
            PrefsBridge.putStringSet(MediaSessionOnlineFallbackPreferences.PACKAGES, packages)
        },
    )
}

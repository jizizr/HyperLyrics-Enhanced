package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.common.MediaSessionOnlineFallbackPreferences
import com.juren233.hyperlyricsenhanced.ui.component.TextInputDialog
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun MediaSessionOnlineFallbackSettings() {
    val prefs = rememberHookPrefs()
    val save = rememberHookConfigSaver(prefs)
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(MediaSessionOnlineFallbackPreferences.ENABLED, false))
    }
    var packages by remember {
        mutableStateOf(prefs.getString(MediaSessionOnlineFallbackPreferences.PACKAGES, "").orEmpty())
    }
    var editing by remember { mutableStateOf(false) }
    TextInputDialog(
        show = editing,
        title = stringResource(R.string.pwa_fallback_packages),
        label = stringResource(R.string.pwa_fallback_packages_edit),
        initialValue = packages,
        onDismiss = { editing = false },
        onConfirm = {
            packages = MediaSessionOnlineFallbackPreferences.parsePackages(it).sorted().joinToString("\n")
            save(MediaSessionOnlineFallbackPreferences.PACKAGES, packages)
        },
    )
    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
        SwitchPreference(
            title = stringResource(R.string.pwa_fallback_title),
            summary = stringResource(R.string.pwa_fallback_summary),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                save(MediaSessionOnlineFallbackPreferences.ENABLED, it)
            },
        )
        ArrowPreference(
            title = stringResource(R.string.pwa_fallback_packages),
            summary = packages.ifBlank { stringResource(R.string.pwa_fallback_packages_empty) },
            onClick = { editing = true },
        )
    }
}

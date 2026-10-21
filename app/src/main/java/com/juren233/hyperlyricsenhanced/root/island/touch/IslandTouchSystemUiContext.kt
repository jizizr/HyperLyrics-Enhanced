/* Copyright 2026 juren233 */
package com.juren233.hyperlyricsenhanced.root.island.touch

import android.content.Context

/**
 * The island host view lives in a plugin whose Context reports package `miui.systemui.plugin`.
 * MediaController transport calls and AudioManager send the Context's package name to the system
 * server, which rejects a package that does not belong to the calling uid (SystemUI, 10230).
 * Media control must therefore start from a Context that reports the SystemUI package.
 */
internal object IslandTouchSystemUiContext {
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /** First candidate reporting the SystemUI package, otherwise [host] (the previous behavior). */
    fun <T : Any> select(host: T, candidates: List<T?>, packageOf: (T) -> String?): T =
        candidates.firstOrNull { it != null && packageOf(it) == SYSTEM_UI_PACKAGE } ?: host

    fun resolve(host: Context): Context = select(
        host,
        listOf(currentApplication(), runCatching { host.applicationContext }.getOrNull()),
        Context::getPackageName,
    )

    private fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()
}

/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import android.content.SharedPreferences

/**
 * 首启流程的版本，按 x.y.z（主.次.修订）定位，逐段比较。
 *
 * [CURRENT] 是「首启流程最近一次需要用户重走」的版本号：只有流程新增了必要步骤、
 * 老用户必须再走一遍时，才把它改成发布该改动的 App 版本（x.y.z）。普通修复和功能更新
 * 不用动它。用户已完成的版本不低于 [CURRENT] 时不再重走。
 *
 * 旧版本把它存成整数 `key_setup_flow_version`（主×10＋次，8.3 → 83），这个公式在次版本
 * 达到 10 时会和下一个主版本撞车，所以改存 x.y.z 字符串到 [UIConstants.KEY_SETUP_FLOW_VERSION_NAME]；
 * 旧整数键仍作为输入读取，按 [fromLegacy] 还原，保证已完成首启的用户不被重复打扰。
 *
 * 变更记录（每次改 [CURRENT] 时追加一行）：
 * - 8.3.0：重做首启流程；必要权限新增「忽略电池优化」。
 */
object SetupFlowVersion {

    data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

        override fun toString(): String = "$major.$minor.$patch"
    }

    val CURRENT = Version(major = 8, minor = 3, patch = 0)

    /** 解析 `8.3.0`，容忍 `v` 前缀与 `-canary`、`+build` 后缀；缺修订号按 0；不合法返回 null。 */
    fun parse(text: String?): Version? {
        val parts = text?.trim()
            ?.removePrefix("v")
            ?.substringBefore('-')
            ?.substringBefore('+')
            ?.split('.')
            ?: return null
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toIntOrNull()?.takeIf { value -> value >= 0 } ?: return null }
        return Version(numbers[0], numbers[1], numbers.getOrElse(2) { 0 })
    }

    /** 还原旧整数键：主×10＋次，修订号恒为 0（8.3 → 8.3.0）。 */
    fun fromLegacy(value: Int): Version? =
        if (value <= 0) null else Version(major = value / 10, minor = value % 10, patch = 0)

    /** 新键优先；新键缺失或无法解析时退回旧整数键；两者都没有表示从未完成。 */
    fun resolveStored(versionName: String?, legacyValue: Int?): Version? =
        parse(versionName) ?: legacyValue?.let(::fromLegacy)

    /** 已存版本不低于 [current] 即视为已完成；从未完成（null）一律需要走。 */
    fun isCompleted(stored: Version?, current: Version = CURRENT): Boolean =
        stored != null && stored >= current

    fun isCompleted(prefs: SharedPreferences, current: Version = CURRENT): Boolean {
        val all = prefs.all
        val stored = resolveStored(
            versionName = all[UIConstants.KEY_SETUP_FLOW_VERSION_NAME] as? String,
            legacyValue = (all[UIConstants.KEY_SETUP_FLOW_VERSION] as? Number)?.toInt(),
        )
        return isCompleted(stored, current)
    }

    /** 记录用户走完了 [current] 版本的流程；调用方负责提交 [editor]。 */
    fun record(editor: SharedPreferences.Editor, current: Version = CURRENT) {
        editor.putString(UIConstants.KEY_SETUP_FLOW_VERSION_NAME, current.toString())
    }
}

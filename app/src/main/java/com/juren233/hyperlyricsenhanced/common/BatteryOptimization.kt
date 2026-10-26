/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.common

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * 电池优化豁免的状态判定与授权入口。
 *
 * 首启流程的必要权限与歌词通知页共用这里的实现，保证「是否已豁免」「如何拉起授权」
 * 只有一处定义。
 */
object BatteryOptimization {

    private const val KEY_VENDOR_POLICY_VISITED = "key_battery_vendor_policy_visited"

    /**
     * 「豁免」这一行是否算已完成。
     *
     * 原生白名单（[isIgnoring]）通过即完成。小米系的「省电策略 → 无限制」属于 powerkeeper 自己的
     * 策略，不会写入原生白名单，`isIgnoringBatteryOptimizations` 始终返回 false（HyperOS 已知行为），
     * 小米官方文档也只提供 `isBackgroundRestricted()` 判断「是否被限制」，没有判断「无限制」的接口。
     * 所以小米系设备上走了弹窗入口（本应用耗电详情页不可用时的退路）、且系统未判定为后台受限，才视为已完成。
     */
    fun isSatisfied(context: Context): Boolean =
        isIgnoring(context) ||
            (FeatureEntryConfig.isXiaomiOrRedmiDevice() && hasVisitedVendorPolicy(context) && !isBackgroundRestricted(context))

    /** 系统是否判定本应用为后台受限（用户选了「受限」）；查询失败按未受限处理。 */
    private fun isBackgroundRestricted(context: Context): Boolean =
        runCatching { context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted }
            .getOrNull() == true

    private fun hasVisitedVendorPolicy(context: Context): Boolean =
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VENDOR_POLICY_VISITED, false)

    /** 是否已被系统原生白名单豁免；查询失败时按未豁免处理，让用户仍有入口可点。 */
    fun isIgnoring(context: Context): Boolean =
        runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull() == true

    /** AOSP 设置里「本应用的耗电详情页」（隐藏常量，但 Settings 自身与 adb 都在用），data 为 package:<包名>。 */
    private const val ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL = "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL"

    /**
     * 拉起豁免授权，尽量一步直达本应用：
     * - 小米系：应用专属授权弹窗会被重定向到 powerkeeper 的省电策略页，那里选「无限制」不写入原生白名单，
     *   所以先打开 AOSP 本应用耗电详情页（在那里选「无限制」会写入白名单），设备上没有该页才退回弹窗。
     * - 其他系统：应用专属授权弹窗。
     * 最后退到系统电池优化总列表（需用户自己找到本应用）。三条入口都无法启动时返回 false。
     */
    @SuppressLint("BatteryLife")
    fun request(context: Context): Boolean {
        val packageUri = "package:${context.packageName}".toUri()
        val detail = Intent(ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL).setData(packageUri)
        val dialog = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(packageUri)
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (FeatureEntryConfig.isXiaomiOrRedmiDevice()) {
            if (canResolve(context, detail) && runCatching { context.startActivity(detail) }.isSuccess) return true
            if (runCatching { context.startActivity(dialog) }.isSuccess) {
                markVendorPolicyVisited(context)
                return true
            }
        } else if (runCatching { context.startActivity(dialog) }.isSuccess) {
            return true
        }
        return runCatching { context.startActivity(list) }.isSuccess
    }

    private fun canResolve(context: Context, intent: Intent): Boolean =
        runCatching { context.packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)

    private fun markVendorPolicyVisited(context: Context) {
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VENDOR_POLICY_VISITED, true).apply()
    }
}

/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider.auto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.juren233.hyperlyricsenhanced.R
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderAcquisitionLog
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCatalog
import com.juren233.hyperlyricsenhanced.provider.ProviderCatalogEntry
import com.juren233.hyperlyricsenhanced.ui.MainActivity
import com.juren233.hyperlyricsenhanced.ui.page.main.OneTapRefreshRequest

/**
 * 插件后台自动更新完成后的提醒：新插件要等音乐 App 重启后才会加载。
 *
 * 点按通知回到模块首页并打开一键刷新面板，预先勾选该音乐 App；
 * 是否重启由用户在面板里自己确认，通知本身不结束任何进程。
 */
object ProviderUpdateNotifier {
    private const val CHANNEL_ID = "provider_auto_update"

    fun notifyUpdated(context: Context, catalog: ProviderCatalogEntry) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val packages = installedTargetPackages(context, catalog)
        if (packages.isEmpty()) return
        val definition = OfficialProviderCatalog.definitions.firstOrNull { it.id == catalog.id }
        val appName = packages
            .map { definition?.displayNameForPackage(it) ?: catalog.displayName }
            .distinct()
            .joinToString(context.getString(R.string.provider_update_notification_name_separator))
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_name_provider_update),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(OneTapRefreshRequest.EXTRA_PACKAGES, packages.toTypedArray())
        val notificationId = CHANNEL_ID.hashCode() + catalog.id.hashCode()
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.lyrictile)
            .setContentTitle(context.getString(R.string.provider_update_notification_title, appName))
            .setContentText(context.getString(R.string.provider_update_notification_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            OfficialProviderAcquisitionLog.warn(
                "插件更新通知发送失败: id=${catalog.id} error=${OfficialProviderAcquisitionLog.describe(e)}",
            )
        }
    }

    private fun installedTargetPackages(context: Context, catalog: ProviderCatalogEntry): List<String> =
        catalog.targetPackages.filter { packageName ->
            runCatching {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L),
                )
            }.isSuccess
        }
}

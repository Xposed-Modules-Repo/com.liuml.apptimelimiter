package com.liuml.apptimelimiter.nonroot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import com.liuml.apptimelimiter.MainActivity
import com.liuml.apptimelimiter.R

/** Posts a passive reminder only when Android currently permits notifications. */
internal class UsageMilestoneNotifier(context: Context) {
    private val appContext = context.applicationContext

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            appContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // canPost checks POST_NOTIFICATIONS immediately above notify.
    fun show(packageName: String, usedMillis: Long, english: Boolean): Boolean {
        if (!canPost()) return false
        val manager = NotificationManagerCompat.from(appContext)
        createChannel()
        val label = runCatching {
            appContext.packageManager.getApplicationLabel(
                appContext.packageManager.getApplicationInfo(packageName, 0),
            ).toString()
        }.getOrDefault(packageName)
        val intent = Intent(appContext, MainActivity::class.java)
            .setData(android.net.Uri.parse("apptimelimiter://settings?section=stats"))
            .putExtra(MainActivity.EXTRA_OPEN_STATISTICS, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntentCompat.getActivity(
            appContext,
            packageName.hashCode() * 53 + (usedMillis / 1_800_000L).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            false,
        )
        return runCatching {
            manager.notify(
                notificationId(packageName),
                NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(if (english) "Usage reminder" else "使用时长提醒")
                    .setContentText(
                        if (english) "$label has been used for ${formatDuration(usedMillis, true)} today"
                        else "今天已使用 $label ${formatDuration(usedMillis, false)}",
                    )
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build(),
            )
            true
        }.getOrDefault(false)
    }

    fun cancel(packageName: String) {
        NotificationManagerCompat.from(appContext).cancel(notificationId(packageName))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        appContext.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    appContext.getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
    }

    private fun formatDuration(usedMillis: Long, english: Boolean): String {
        val minutes = (usedMillis.coerceAtLeast(0L) / 60_000L).toInt()
        val hours = minutes / 60
        val remainder = minutes % 60
        return if (english) when {
            hours == 0 -> "$remainder min"
            remainder == 0 -> "$hours h"
            else -> "$hours h $remainder min"
        } else when {
            hours == 0 -> "$remainder 分钟"
            remainder == 0 -> "$hours 小时"
            else -> "$hours 小时 $remainder 分钟"
        }
    }

    private fun notificationId(packageName: String): Int =
        0x51000000 or (packageName.hashCode() and 0x00ffffff)

    private companion object {
        const val CHANNEL_ID = "usage_milestone_reminders"
    }
}

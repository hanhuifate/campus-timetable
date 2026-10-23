package com.njfu.timetable.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

/**
 * 状态栏提醒的渠道与通知构造。
 * 震动/提示音由两个通知渠道区分：Android 里渠道建好后声音不能改，只能换渠道。
 */
object ReminderNotifier {

    const val CHANNEL_VIBRATE = "reminder_vibrate"
    const val CHANNEL_SOUND = "reminder_sound"
    private const val NOTIFICATION_ID = 20261023

    fun notify(context: Context, title: String, body: String, sound: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels(context)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val content = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(
            context,
            if (sound) CHANNEL_SOUND else CHANNEL_VIBRATE
        )
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(content)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val vibrate = NotificationChannel(
            CHANNEL_VIBRATE,
            "上课提醒（仅震动）",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "上课前提醒，只震动不响铃"
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 320, 180, 320)
        }

        val sound = NotificationChannel(
            CHANNEL_SOUND,
            "上课提醒（震动 + 提示音）",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "上课前提醒，震动并播放提示音"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 320, 180, 320)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }

        manager.createNotificationChannel(vibrate)
        manager.createNotificationChannel(sound)
    }
}

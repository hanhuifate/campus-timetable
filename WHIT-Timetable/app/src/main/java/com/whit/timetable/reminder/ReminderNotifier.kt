package com.whit.timetable.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build

/**
 * 状态栏提醒的渠道与通知构造。
 * Android 里通知渠道的声音建好后不能修改，所以每种提示音对应一个独立渠道，按设置切换渠道。
 */
object ReminderNotifier {

    const val TONE_VIBRATE = "vibrate"
    const val TONE_NOTIF = "notif"
    const val TONE_ALARM = "alarm"
    const val TONE_RINGTONE = "ringtone"

    /** 设置页展示顺序。 */
    val TONES = listOf(TONE_VIBRATE, TONE_NOTIF, TONE_ALARM, TONE_RINGTONE)

    const val CHANNEL_VIBRATE = "reminder_vibrate"
    private const val CHANNEL_NOTIF = "reminder_sound"
    private const val CHANNEL_ALARM = "reminder_alarm"
    private const val CHANNEL_RINGTONE = "reminder_ringtone"

    private const val NOTIFICATION_ID = 20261023
    private const val PREVIEW_ID = 20261024

    fun label(tone: String): String = when (tone) {
        TONE_NOTIF -> "通知音"
        TONE_ALARM -> "闹钟音"
        TONE_RINGTONE -> "铃声"
        else -> "仅震动"
    }

    fun notify(context: Context, title: String, body: String, tone: String) {
        post(context, NOTIFICATION_ID, title, body, tone)
    }

    /** 设置页的试听：发一条示例通知，看到的样子和正式提醒一样。 */
    fun preview(context: Context, minutes: Int, tone: String) {
        post(
            context,
            PREVIEW_ID,
            "快要上课：高等数学A(1)",
            "示例：第1-2节 08:30-10:05 · 6号楼 6202，${minutes} 分钟后上课",
            tone
        )
    }

    private fun post(
        context: Context,
        id: Int,
        title: String,
        body: String,
        tone: String
    ) {
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

        val builder = Notification.Builder(context, channelId(tone))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(content)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)

        // 同一条通知还在通知栏时再发不会重新响铃，先撤掉再发
        manager.cancel(id)
        manager.notify(id, builder.build())
    }

    private fun channelId(tone: String): String = when (tone) {
        TONE_NOTIF -> CHANNEL_NOTIF
        TONE_ALARM -> CHANNEL_ALARM
        TONE_RINGTONE -> CHANNEL_RINGTONE
        else -> CHANNEL_VIBRATE
    }

    private fun toneUri(tone: String): Uri? = when (tone) {
        TONE_NOTIF -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        TONE_ALARM -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        TONE_RINGTONE -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        else -> null
    }

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channels = listOf(
            CHANNEL_VIBRATE to "上课提醒（仅震动）",
            CHANNEL_NOTIF to "上课提醒（通知音）",
            CHANNEL_ALARM to "上课提醒（闹钟音）",
            CHANNEL_RINGTONE to "上课提醒（铃声）"
        )
        channels.forEach { (id, name) ->
            val uri = when (id) {
                CHANNEL_NOTIF -> toneUri(TONE_NOTIF)
                CHANNEL_ALARM -> toneUri(TONE_ALARM)
                CHANNEL_RINGTONE -> toneUri(TONE_RINGTONE)
                else -> null
            }
            val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 320, 180, 320)
                if (uri != null) {
                    setSound(
                        uri,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    setSound(null, null)
                    description = "上课前提醒，只震动不响铃"
                }
            }
            manager.createNotificationChannel(channel)
        }
    }
}

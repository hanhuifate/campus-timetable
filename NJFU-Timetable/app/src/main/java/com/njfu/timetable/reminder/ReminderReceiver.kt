package com.njfu.timetable.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.njfu.timetable.data.AppPrefs

/**
 * 到点后发状态栏提醒，并立刻排下一条，保证 App 不在前台也能连续提醒。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AppPrefs(context)
        val name = intent.getStringExtra("name").orEmpty()
        val startAt = intent.getLongExtra("startAt", 0L)
        val sectionStart = intent.getIntExtra("start", 1)
        val sectionEnd = intent.getIntExtra("end", sectionStart)
        val time = intent.getStringExtra("time").orEmpty()
        val room = intent.getStringExtra("room").orEmpty()

        // 关机等原因导致迟到超过半小时就不打扰了，只排下一条
        if (prefs.remindEnabled && name.isNotEmpty() && startAt > 0L &&
            System.currentTimeMillis() - startAt < 30 * 60_000L
        ) {
            val minutes = ((startAt - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0)
            val detail = buildString {
                append("第${sectionStart}-${sectionEnd}节")
                if (time.isNotEmpty()) append(" $time")
                if (room.isNotEmpty()) append(" · $room")
                append(if (minutes > 0) "，${minutes} 分钟后上课" else "，即将上课")
            }
            ReminderNotifier.notify(context, "快要上课：$name", detail, prefs.remindTone, startAt)
        }

        ReminderScheduler.schedule(context)
    }
}

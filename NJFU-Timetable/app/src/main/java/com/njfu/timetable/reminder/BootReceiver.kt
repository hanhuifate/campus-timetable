package com.njfu.timetable.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机、改时间、重装后重新排一次提醒，避免闹钟被系统清空后失效。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.schedule(context)
    }
}

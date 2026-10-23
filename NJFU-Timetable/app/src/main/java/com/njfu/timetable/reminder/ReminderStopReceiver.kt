package com.njfu.timetable.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知上的「停止」按钮：停掉提示音并撤掉通知。 */
class ReminderStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ReminderNotifier.stop(context)
    }
}

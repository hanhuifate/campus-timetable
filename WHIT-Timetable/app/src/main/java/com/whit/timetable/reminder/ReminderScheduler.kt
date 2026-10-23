package com.whit.timetable.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.whit.timetable.data.AppPrefs
import com.whit.timetable.data.Course
import com.whit.timetable.data.SectionTimes
import com.whit.timetable.data.TimetableStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 上课提醒调度：每次只排“最近的一条”，触发后在接收器里继续排下一条。
 * 用 AlarmManager 精确闹钟，App 进程不在运行时依然会响。
 */
object ReminderScheduler {

    /** 往后找多少天，足够覆盖长假期间不打开 App 的情况。 */
    private const val LOOKAHEAD_DAYS = 21L
    private const val REQUEST_CODE = 20261023

    data class Due(val course: Course, val startAt: Long, val triggerAt: Long)

    fun schedule(context: Context) {
        val prefs = AppPrefs(context)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pending(context))

        val due = next(context) ?: return
        if (!prefs.remindEnabled) return
        val pi = pending(context, due)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due.triggerAt, pi)
            } else {
                // 没拿到精确闹钟权限时退化为窗口闹钟，误差通常在一分钟内
                alarm.setWindow(AlarmManager.RTC_WAKEUP, due.triggerAt, 60_000L, pi)
            }
        } catch (e: SecurityException) {
            alarm.setWindow(AlarmManager.RTC_WAKEUP, due.triggerAt, 60_000L, pi)
        }
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pending(context))
    }

    /** 距离现在最近的一条提醒；没有课或已全部上完时返回 null。 */
    fun next(context: Context): Due? {
        val prefs = AppPrefs(context)
        if (!prefs.remindEnabled) return null

        val courses = TimetableStore(context).loadCourses()
        if (courses.isEmpty()) return null

        val termStart = runCatching { LocalDate.parse(prefs.termStart) }.getOrNull() ?: return null
        val campus = SectionTimes.campusOf(courses)
        val leadMs = prefs.remindMinutes.coerceIn(1, 180) * 60_000L
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val now = System.currentTimeMillis()

        for (offset in 0..LOOKAHEAD_DAYS) {
            val date = today.plusDays(offset)
            val week = ((date.toEpochDay() - termStart.toEpochDay()) / 7).toInt() + 1
            if (week < 1) continue

            val todays = courses
                .filter { it.day == date.dayOfWeek.value && it.weeks.contains(week) }
                .sortedBy { it.startSection }
            var best: Due? = null

            todays.forEachIndexed { index, course ->
                // 同一天里紧挨着上一段同名课程的连续节次，只在第一段提醒一次
                val repeated = todays.take(index).any {
                    it.name == course.name && it.endSection + 1 == course.startSection
                }
                if (repeated) return@forEachIndexed

                val clock = runCatching {
                    LocalTime.parse(SectionTimes.start(campus, course.startSection))
                }.getOrNull() ?: return@forEachIndexed
                val startAt = LocalDateTime.of(date, clock)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
                val triggerAt = startAt - leadMs
                if (triggerAt <= now + 3_000L) return@forEachIndexed
                if (best == null || triggerAt < best!!.triggerAt) {
                    best = Due(course, startAt, triggerAt)
                }
            }
            if (best != null) return best
        }
        return null
    }

    private fun pending(context: Context, due: Due? = null): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
        if (due != null) {
            val course = due.course
            intent.putExtra("name", course.name)
            intent.putExtra("room", SectionTimes.shortRoom(course.room))
            intent.putExtra("start", course.startSection)
            intent.putExtra("end", course.endSection)
            intent.putExtra(
                "time",
                SectionTimes.range(SectionTimes.campusOf(TimetableStore(context).loadCourses()), course.startSection, course.endSection)
            )
            intent.putExtra("startAt", due.startAt)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

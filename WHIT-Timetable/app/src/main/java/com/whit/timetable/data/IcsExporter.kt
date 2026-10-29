package com.whit.timetable.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 把当前课表导出为 iCalendar（.ics）文件。
 * iPhone 上打开这个文件就能一次性导入系统日历，由日历自己按周提醒，
 * 不依赖 App 常驻后台，也不受 iOS 后台限制。
 */
object IcsExporter {

    /** 生成文件并通过系统分享面板发出去（微信、邮件、AirDrop 都可以）。 */
    fun share(context: Context): Boolean {
        val courses = TimetableStore(context).loadCourses()
        if (courses.isEmpty()) return false
        val dir = File(context.cacheDir, "calendar").apply { mkdirs() }
        val file = File(dir, "课表.ics")
        file.writeText(build(context, courses))
        val uri = Uri.parse("content://${context.packageName}.ics/timetable.ics")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/calendar"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "课表")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "导出课表").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return true
    }

    fun build(context: Context, courses: List<Course> = TimetableStore(context).loadCourses()): String {
        val prefs = AppPrefs(context)
        val termStart = parseDate(prefs.termStart) ?: return ""
        val alarmsOn = prefs.remindEnabled
        val lead = prefs.remindMinutes
        val sb = StringBuilder()
        fun line(s: String) = sb.append(s).append("\r\n")

        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//CampusTimetable//WHIT//CN")
        line("CALSCALE:GREGORIAN")
        line("METHOD:PUBLISH")
        line("X-WR-CALNAME:${escape(prefs.termName)}")
        line("X-WR-TIMEZONE:Asia/Shanghai")
        // 中国无夏令时，给一个固定 +0800 的时区定义，iPhone 就能按本地时间解析
        line("BEGIN:VTIMEZONE")
        line("TZID:Asia/Shanghai")
        line("BEGIN:STANDARD")
        line("DTSTART:19700101T000000")
        line("TZOFFSETFROM:+0800")
        line("TZOFFSETTO:+0800")
        line("TZNAME:CST")
        line("END:STANDARD")
        line("END:VTIMEZONE")

        val stamp = utcStamp()
        for (course in courses) {
            val begin = SectionTimes.start("银湖", course.startSection)
            val end = SectionTimes.end("银湖", course.endSection)
            if (begin.isEmpty() || end.isEmpty()) continue
            for (week in course.weeks.sorted()) {
                if (week < 1) continue
                val day = Calendar.getInstance().apply {
                    time = termStart
                }.apply {
                    add(Calendar.DAY_OF_MONTH, (week - 1) * 7 + (course.day - 1))
                }
                line("BEGIN:VEVENT")
                line("UID:${uid(course, week)}")
                line("DTSTAMP:$stamp")
                line("DTSTART;TZID=Asia/Shanghai:${dateTime(day, begin)}")
                line("DTEND;TZID=Asia/Shanghai:${dateTime(day, end)}")
                line("SUMMARY:${escape(course.name)}")
                if (course.room.isNotBlank()) line("LOCATION:${escape(course.room)}")
                line("DESCRIPTION:${escape(description(course, begin, end, week))}")
                if (alarmsOn) {
                    line("BEGIN:VALARM")
                    line("TRIGGER:-PT${lead}M")
                    line("ACTION:DISPLAY")
                    line("DESCRIPTION:${escape("快要上课：${course.name}")}")
                    line("END:VALARM")
                }
                line("END:VEVENT")
            }
        }
        line("END:VCALENDAR")
        return sb.toString()
    }

    private fun description(course: Course, begin: String, end: String, week: Int): String {
        val sb = StringBuilder()
        sb.append("第${course.startSection}-${course.endSection}节 $begin-$end")
        if (course.teacher.isNotBlank()) sb.append("\\n教师：${course.teacher}")
        sb.append("\\n第${week}周")
        return sb.toString()
    }

    /** 同一条课程在日历里保持稳定的 UID，重复导入不会变成两堆重复事件。 */
    private fun uid(course: Course, week: Int): String {
        val key = "${course.name}|${course.day}|${course.startSection}|${course.endSection}|$week"
        val md5 = MessageDigest.getInstance("MD5").digest(key.toByteArray(Charsets.UTF_8))
        return md5.joinToString("") { "%02x".format(it) } + "@campus-timetable"
    }

    private fun parseDate(text: String): Date? = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { isLenient = false }.parse(text)
    } catch (e: Exception) {
        null
    }

    /** "08:30" + 日期 -> 20260831T083000 */
    private fun dateTime(day: Calendar, hm: String): String {
        val parts = hm.split(':')
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.CHINA).apply {
            timeZone = TimeZone.getDefault()
        }.format(Calendar.getInstance().apply {
            time = day.time
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
        }.time)
    }

    private fun utcStamp(): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    /** iCalendar 文本转义：反斜杠、分号、逗号、换行。 */
    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
}

package com.njfu.timetable.data

import org.json.JSONObject

data class Course(
    val name: String,
    val teacher: String,
    val room: String,
    val day: Int,          // 1 = 周一 ... 7 = 周日
    val startSection: Int, // 从 1 开始
    val endSection: Int,
    val weeks: Set<Int>,
    val colorIndex: Int,
    val id: String = "",
    val custom: Boolean = false   // 自定义课程：可编辑，教务同步不会覆盖
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("teacher", teacher)
        put("room", room)
        put("day", day)
        put("start", startSection)
        put("end", endSection)
        put("weeks", weeksToText(weeks))
        put("color", colorIndex)
        put("id", id.ifEmpty { identity() })
        put("custom", custom)
    }

    /** 用于稳定标识一条课程，老数据没有 id 时按内容生成。 */
    fun identity(): String =
        "$name|$teacher|$room|$day|$startSection|$endSection|${weeks.sorted()}"

    companion object {
        fun fromJson(o: JSONObject): Course = Course(
            name = o.optString("name"),
            teacher = o.optString("teacher"),
            room = o.optString("room"),
            day = o.optInt("day", 1),
            startSection = o.optInt("start", 1),
            endSection = o.optInt("end", 1),
            weeks = textToWeeks(o.optString("weeks")),
            colorIndex = o.optInt("color", 0),
            id = o.optString("id"),
            custom = o.optBoolean("custom", false)
        )

        /** "1-16,18" -> set */
        fun textToWeeks(text: String): Set<Int> {
            val result = mutableSetOf<Int>()
            if (text.isBlank()) return result
            text.split(',').forEach { part ->
                val p = part.trim()
                if (p.isEmpty()) return@forEach
                if (p.contains('-')) {
                    val bounds = p.split('-')
                    val a = bounds.getOrNull(0)?.trim()?.toIntOrNull() ?: return@forEach
                    val b = bounds.getOrNull(1)?.trim()?.toIntOrNull() ?: a
                    for (w in a..b) result.add(w)
                } else {
                    p.toIntOrNull()?.let { result.add(it) }
                }
            }
            return result
        }

        /** set -> "1-16,18" */
        fun weeksToText(weeks: Set<Int>): String {
            if (weeks.isEmpty()) return ""
            val sorted = weeks.sorted()
            val sb = StringBuilder()
            var start = sorted.first()
            var prev = start
            for (i in 1 until sorted.size) {
                val cur = sorted[i]
                if (cur == prev + 1) {
                    prev = cur
                } else {
                    if (sb.isNotEmpty()) sb.append(',')
                    sb.append(if (start == prev) "$start" else "$start-$prev")
                    start = cur
                    prev = cur
                }
            }
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(if (start == prev) "$start" else "$start-$prev")
            return sb.toString()
        }
    }
}

data class TimetableMeta(
    val termName: String = "",
    val startDate: String = "",   // 学期第一周周一 yyyy-MM-dd
    val updatedAt: Long = 0L,
    val syncState: String = "",
    val studentName: String = ""
)

/**
 * 学校作息时间表（第 1..12 节）。
 * 白马校区上午比新庄/淮安晚半小时；淮安与新庄作息一致。
 * 校区由用户在设置里选择，只影响时间显示，不影响课表内容。
 */
object SectionTimes {
    val CAMPUSES = listOf("白马", "新庄", "淮安")

    private val BAI = arrayOf(
        "08:30-09:15", "09:20-10:05", "10:25-11:10", "11:15-12:00",
        "14:00-14:45", "14:50-15:35", "15:55-16:40", "16:45-17:30",
        "18:30-19:15", "19:20-20:05", "20:10-20:55", "21:00-21:45"
    )
    private val XIN = arrayOf(
        "08:00-08:45", "08:55-09:40", "10:00-10:45", "10:55-11:40",
        "14:00-14:45", "14:50-15:35", "15:55-16:40", "16:45-17:30",
        "18:30-19:15", "19:20-20:05", "20:10-20:55", "21:00-21:45"
    )

    /** 按课表里的教室推断校区，仅用于未设置时的兜底。 */
    fun campusOf(courses: List<Course>): String {
        val bai = courses.count { it.room.contains("白马") }
        val xin = courses.count {
            it.room.contains("新庄") || it.room.contains("淮安")
        }
        return if (bai > xin) "白马" else "新庄"
    }

    fun normalize(campus: String): String = when {
        campus.contains("白马") -> "白马"
        campus.contains("淮安") -> "淮安"
        else -> "新庄"
    }

    private fun table(campus: String) = when (normalize(campus)) {
        "白马" -> BAI
        else -> XIN
    }

    fun start(campus: String, section: Int): String =
        table(campus).getOrNull(section - 1)?.substringBefore('-') ?: ""

    fun end(campus: String, section: Int): String =
        table(campus).getOrNull(section - 1)?.substringAfter('-') ?: ""

    /** "08:30-10:05" */
    fun range(campus: String, startSection: Int, endSection: Int): String {
        val a = start(campus, startSection)
        val b = end(campus, endSection)
        return if (a.isEmpty() || b.isEmpty()) "" else "$a-$b"
    }

    /** 去掉重复的校区前缀，便于在窄格子里直接显示教室。 */
    fun shortRoom(room: String): String = room
        .replace("白马校区", "")
        .replace("新庄校区", "")
        .replace("淮安校区", "")
        .replace("-", " ")
        .replace("（）", "")
        .replace("()", "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

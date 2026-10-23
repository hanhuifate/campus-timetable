package com.whit.timetable.data

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
 * 芜湖职业技术大学作息时间表（第 1..11 节，按教务接口返回的时间）。
 */
object SectionTimes {
    private val TIMES = arrayOf(
        "08:00-08:45", "08:55-09:40", "10:00-10:45", "10:55-11:40",
        "14:00-14:45", "14:55-15:40", "15:50-16:35", "16:45-17:30",
        "19:00-19:45", "19:55-20:40", "20:50-21:35"
    )

    fun campusOf(courses: List<Course>): String = "银湖"

    fun start(campus: String, section: Int): String =
        TIMES.getOrNull(section - 1)?.substringBefore('-') ?: ""

    fun end(campus: String, section: Int): String =
        TIMES.getOrNull(section - 1)?.substringAfter('-') ?: ""

    /** "08:00-10:35" */
    fun range(campus: String, startSection: Int, endSection: Int): String {
        val a = start(campus, startSection)
        val b = end(campus, endSection)
        return if (a.isEmpty() || b.isEmpty()) "" else "$a-$b"
    }

    /** 去掉校区前缀，同时清掉去掉校区后留下的空括号。 */
    fun shortRoom(room: String): String = room
        .replace("银湖校区", "")
        .replace("银湖", "")
        .replace("文津校区", "")
        .replace("文津", "")
        .replace("（）", "")
        .replace("()", "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

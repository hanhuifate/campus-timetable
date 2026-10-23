package com.whit.timetable.net

import com.whit.timetable.data.Course
import org.json.JSONObject
import kotlin.math.abs

/**
 * 解析芜湖金智 jwgl 课表接口返回的 list。
 * 每条记录是一节课：week=星期几(1..7)，section=行号(1..15)，
 * bw/ew=起止周，beWeekList 里带单双周信息。
 */
object WhitParser {

    private const val COLOR_COUNT = 8

    /** 表格行号 -> 实际节次。中午1/2（5、6 行）暂不排课。 */
    private fun sectionToPeriod(section: Int): Int = when (section) {
        in 1..4 -> section
        in 7..13 -> section - 2
        else -> 0
    }

    fun parse(json: String): List<Course> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return emptyList()
        }
        val list = root.optJSONArray("list") ?: return emptyList()

        // (课程|教师|教室|星期|周次) -> 节次集合，用于把连堂课合成一块
        val grouped = LinkedHashMap<String, MutableSet<Int>>()
        val seeds = HashMap<String, Course>()

        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val name = item.optString("lessonName").trim()
            if (name.isEmpty()) continue
            val day = item.optInt("week", 0)
            if (day !in 1..7) continue
            val period = sectionToPeriod(item.optInt("section", 0))
            if (period == 0) continue
            val weeks = weeksOf(item)
            if (weeks.isEmpty()) continue

            val teacher = item.optString("tchName").trim()
            val room = item.optString("crName").trim()
            val key = "$name|$teacher|$room|$day|${weeks.sorted()}"
            val periods = grouped.getOrPut(key) { sortedSetOf() }
            periods.add(period)
            if (!seeds.containsKey(key)) {
                seeds[key] = Course(
                    name = name,
                    teacher = teacher,
                    room = room,
                    day = day,
                    startSection = period,
                    endSection = period,
                    weeks = weeks,
                    colorIndex = abs(name.hashCode()) % COLOR_COUNT
                )
            }
        }

        val result = ArrayList<Course>()
        for ((key, periods) in grouped) {
            val base = seeds[key] ?: continue
            var runStart = 0
            var prev = 0
            for (p in periods.sorted()) {
                if (runStart == 0) {
                    runStart = p
                    prev = p
                } else if (p == prev + 1) {
                    prev = p
                } else {
                    result.add(base.copy(startSection = runStart, endSection = prev))
                    runStart = p
                    prev = p
                }
            }
            if (runStart != 0) {
                result.add(base.copy(startSection = runStart, endSection = prev))
            }
        }

        return mergeSameSlot(mergeAdjacent(mergeSameSlot(result)))
            .sortedWith(compareBy({ it.day }, { it.startSection }, { it.name }))
    }

    /**
     * 教务有时把连堂课按周次拆成两条（例如第5节 6-10周、第6节 6-9周），
     * 这里把「同课程+同教师+同教室+星期相同+节次相邻+周次有交集」的接回去。
     */
    private fun mergeAdjacent(list: List<Course>): List<Course> {
        val sorted = list.sortedWith(
            compareBy({ it.day }, { it.startSection }, { it.endSection }, { it.room })
        )
        val out = ArrayList<Course>(sorted.size)
        for (c in sorted) {
            val prev = out.lastOrNull()
            if (prev != null &&
                prev.name == c.name &&
                prev.teacher == c.teacher &&
                prev.room == c.room &&
                prev.day == c.day &&
                prev.endSection + 1 == c.startSection &&
                prev.weeks.intersect(c.weeks).isNotEmpty()
            ) {
                out[out.lastIndex] =
                    prev.copy(endSection = c.endSection, weeks = prev.weeks + c.weeks)
            } else {
                out.add(c)
            }
        }
        return out
    }

    /**
     * 教务会给同一节课拆出多条记录（不同周次、不同场地），
     * 这里把「同课程+同教师+同星期+同节次」的合成一张卡：周次取并集，场地去重后并列。
     */
    private fun mergeSameSlot(list: List<Course>): List<Course> {
        val merged = LinkedHashMap<String, Course>()
        for (c in list) {
            val key = "${c.name}|${c.teacher}|${c.day}|${c.startSection}|${c.endSection}"
            val exist = merged[key]
            if (exist == null) {
                merged[key] = c.copy(room = cleanRoom(c.room))
            } else {
                val rooms = (exist.room.split(" / ") + c.room.split(" / "))
                    .map { cleanRoom(it) }
                    .filter { it.isNotEmpty() }
                    .distinct()
                merged[key] = exist.copy(
                    room = rooms.joinToString(" / "),
                    weeks = exist.weeks + c.weeks
                )
            }
        }
        return merged.values.toList()
    }

    private fun cleanRoom(room: String): String = room
        .replace("（）", "")
        .replace("()", "")
        .replace("null", "")
        .trim()

    private fun weeksOf(item: JSONObject): Set<Int> {
        val result = mutableSetOf<Int>()
        val list = item.optJSONArray("beWeekList")
        if (list != null && list.length() > 0) {
            for (i in 0 until list.length()) {
                val w = list.optJSONObject(i) ?: continue
                val a = w.optInt("begin", 0)
                val b = w.optInt("end", 0)
                if (a !in 1..30 || b < a) continue
                val name = w.optString("wtName")
                for (week in a..b) {
                    if (name.contains("单") && week % 2 == 0) continue
                    if (name.contains("双") && week % 2 == 1) continue
                    result.add(week)
                }
            }
        }
        if (result.isEmpty()) {
            val a = item.optInt("bw", 0)
            val b = item.optInt("ew", 0)
            if (a in 1..30 && b >= a) {
                val name = item.optString("wtName")
                for (week in a..b) {
                    if (name.contains("单") && week % 2 == 0) continue
                    if (name.contains("双") && week % 2 == 1) continue
                    result.add(week)
                }
            }
        }
        return result
    }
}

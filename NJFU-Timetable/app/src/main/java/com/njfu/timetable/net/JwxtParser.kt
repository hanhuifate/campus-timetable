package com.njfu.timetable.net

import com.njfu.timetable.data.Course
import org.json.JSONObject
import kotlin.math.abs

/**
 * 解析 jwglxt 课表接口返回的 JSON（kbList）。
 */
object JwxtParser {

    private const val COLOR_COUNT = 8

    fun parse(json: String): List<Course> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return emptyList()
        }
        val list = root.optJSONArray("kbList") ?: return emptyList()
        val result = ArrayList<Course>()

        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val name = item.optString("kcmc").trim()
            if (name.isEmpty()) continue
            val day = item.optString("xqj").trim().toIntOrNull() ?: continue
            if (day !in 1..7) continue

            val sectionText = item.optString("jcs").ifBlank { item.optString("jc") }
            val numbers = Regex("\\d{1,2}").findAll(sectionText)
                .mapNotNull { it.value.toIntOrNull() }
                .toList()
            if (numbers.isEmpty()) continue
            val start = numbers.first().coerceIn(1, 12)
            val end = (numbers.lastOrNull() ?: start).coerceIn(start, 12)

            val weeks = parseWeeks(item.optString("zcd"))
            if (weeks.isEmpty()) continue

            result.add(
                Course(
                    name = name,
                    teacher = teacherOf(item),
                    room = item.optString("cdmc").trim(),
                    day = day,
                    startSection = start,
                    endSection = end,
                    weeks = weeks,
                    colorIndex = abs(name.hashCode()) % COLOR_COUNT
                )
            )
        }

        return result
            .distinctBy {
                "${it.name}|${it.day}|${it.startSection}|${it.endSection}|${it.teacher}|${it.weeks.sorted()}"
            }
            .sortedWith(compareBy({ it.day }, { it.startSection }, { it.name }))
    }

    /**
     * 教师名：正方接口的 xm 字段在多位教师时会被截断成「李春,焦..」，
     * 这里优先用 jsxx（工号/姓名/职称）取完整姓名，取不到再退回 xm。
     */
    private fun teacherOf(item: JSONObject): String {
        val raw = item.optString("jsxx").trim()
        if (raw.isNotEmpty()) {
            val names = raw.split(",", "，", ";", "；").mapNotNull { part ->
                val pieces = part.trim().split("/", "／").map { it.trim() }.filter { it.isNotEmpty() }
                pieces.firstOrNull { it.length in 2..5 && NAME.matches(it) }
            }.filter { it.isNotEmpty() }.distinct()
            if (names.isNotEmpty()) return names.joinToString("、")
        }
        return item.optString("xm").trim()
            .replace(",", "、")
            .replace("..", "…")
    }

    private val NAME = Regex("[\\u4e00-\\u9fa5·]{2,5}")

    private fun parseWeeks(text: String): Set<Int> {
        val result = mutableSetOf<Int>()
        Regex("(\\d{1,2})\\s*(?:[-~～]\\s*(\\d{1,2}))?\\s*周")
            .findAll(text)
            .forEach { match ->
                val a = match.groupValues[1].toIntOrNull() ?: return@forEach
                val b = match.groupValues[2].toIntOrNull() ?: a
                if (a in 1..30 && b in a..30) {
                    for (w in a..b) result.add(w)
                }
            }
        if (result.isEmpty()) return result

        val odd = Regex("单周|[(（]单[)）]").containsMatchIn(text)
        val even = Regex("双周|[(（]双[)）]").containsMatchIn(text)
        if (odd && !even) return result.filter { it % 2 == 1 }.toSet()
        if (even && !odd) return result.filter { it % 2 == 0 }.toSet()
        return result
    }
}

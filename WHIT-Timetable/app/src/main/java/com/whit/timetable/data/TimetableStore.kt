package com.whit.timetable.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 课表与元数据的本地存储。全部文件放在 app 私有目录，启动时只读这一个文件，
 * 不依赖数据库初始化，保证冷启动首帧就能画出课表。
 */
class TimetableStore(context: Context) {

    private val dir = File(context.filesDir, "timetable").apply { mkdirs() }
    private val courseFile = File(dir, "courses.json")
    private val metaFile = File(dir, "meta.json")
    private val diagFile = File(dir, "diag.log")

    fun loadCourses(): List<Course> {
        if (!courseFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(courseFile.readText())
            val list = ArrayList<Course>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val c = Course.fromJson(o)
                if (c.name.isNotEmpty()) list.add(c)
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCourses(courses: List<Course>) {
        val arr = JSONArray()
        courses.forEach { arr.put(it.toJson()) }
        courseFile.writeText(arr.toString())
    }

    data class MergeResult(val trimmed: Int, val removed: Int) {
        val changed: Int get() = trimmed + removed
    }

    /**
     * 教务课表更新时保留用户自建的课程。
     * 自定义课程与教务课程同一天、周次有交集且节次重叠时，只保留不重叠的部分：
     * 重叠的周次裁掉被占用的节次，其余周次原样保留；整段都被占用则删除。
     */
    fun saveImported(imported: List<Course>): MergeResult {
        val official = imported.filter { !it.custom }
        var trimmed = 0
        var removed = 0
        val kept = ArrayList<Course>()
        loadCourses().filter { it.custom }.forEach { custom ->
            val pieces = trimAgainst(custom, official)
            if (pieces.isEmpty()) {
                removed++
            } else {
                if (pieces.size != 1 || pieces[0].startSection != custom.startSection ||
                    pieces[0].endSection != custom.endSection || pieces[0].weeks != custom.weeks
                ) {
                    trimmed++
                }
                kept.addAll(pieces)
            }
        }
        saveCourses(official + kept)
        return MergeResult(trimmed, removed)
    }

    /** 从自定义课程里扣掉教务课程占用的节次，必要时按周次 / 节次拆成多段。 */
    private fun trimAgainst(custom: Course, official: List<Course>): List<Course> {
        data class Part(val weeks: Set<Int>, val start: Int, val end: Int)

        var parts = listOf(Part(custom.weeks, custom.startSection, custom.endSection))
        for (other in official) {
            if (other.day != custom.day) continue
            val next = ArrayList<Part>()
            for (part in parts) {
                val overlapWeeks = part.weeks.intersect(other.weeks)
                if (overlapWeeks.isEmpty() ||
                    other.endSection < part.start || other.startSection > part.end
                ) {
                    next.add(part)
                    continue
                }
                // 不重叠的周次保持原样
                val restWeeks = part.weeks - overlapWeeks
                if (restWeeks.isNotEmpty()) next.add(Part(restWeeks, part.start, part.end))
                // 重叠的周次里把被占用的节次挖掉，剩下的连续节次各成一段
                var runStart = -1
                for (s in part.start..part.end) {
                    val blocked = s in other.startSection..other.endSection
                    if (!blocked && runStart < 0) runStart = s
                    if ((blocked || s == part.end) && runStart >= 0) {
                        val runEnd = if (blocked) s - 1 else s
                        if (runEnd >= runStart) next.add(Part(overlapWeeks, runStart, runEnd))
                        runStart = -1
                    }
                }
            }
            parts = next
        }
        return parts.filter { it.weeks.isNotEmpty() && it.start <= it.end }.map {
            // 被裁剪/拆分的课程换新 id，避免与原来的课程共用同一个标识
            val unchanged = parts.size == 1 && it.start == custom.startSection &&
                it.end == custom.endSection && it.weeks == custom.weeks
            custom.copy(
                startSection = it.start,
                endSection = it.end,
                weeks = it.weeks,
                id = if (unchanged) custom.id else ""
            )
        }
    }

    fun loadMeta(): TimetableMeta {
        if (!metaFile.exists()) return TimetableMeta()
        return try {
            val o = JSONObject(metaFile.readText())
            TimetableMeta(
                termName = o.optString("termName"),
                startDate = o.optString("startDate"),
                updatedAt = o.optLong("updatedAt"),
                syncState = o.optString("syncState"),
                studentName = o.optString("studentName")
            )
        } catch (e: Exception) {
            TimetableMeta()
        }
    }

    fun saveMeta(meta: TimetableMeta) {
        val o = JSONObject()
            .put("termName", meta.termName)
            .put("startDate", meta.startDate)
            .put("updatedAt", meta.updatedAt)
            .put("syncState", meta.syncState)
            .put("studentName", meta.studentName)
        metaFile.writeText(o.toString())
    }

    fun log(line: String) {
        try {
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                .format(java.util.Date())
            diagFile.appendText("[$stamp] $line\n")
        } catch (_: Exception) {
        }
    }

    fun readDiag(): String = try {
        if (diagFile.exists()) diagFile.readText().takeLast(20000) else "暂无诊断记录"
    } catch (e: Exception) {
        "读取失败：${e.message}"
    }

    /** 抓取阶段把页面结构落到文件，便于事后校准解析规则。 */
    fun saveDump(name: String, content: String) {
        try {
            File(dir, name).writeText(content)
        } catch (_: Exception) {
        }
    }

    fun clearDiag() {
        runCatching { diagFile.delete() }
    }
}

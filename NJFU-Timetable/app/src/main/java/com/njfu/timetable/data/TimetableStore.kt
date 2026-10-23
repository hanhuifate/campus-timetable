package com.njfu.timetable.data

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

    /** 教务课表更新时保留用户自建的课程，避免同步被覆盖。 */
    fun saveImported(imported: List<Course>) {
        val custom = loadCourses().filter { it.custom }
        saveCourses(imported.filter { !it.custom } + custom)
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

    fun clearDiag() {
        runCatching { diagFile.delete() }
    }
}

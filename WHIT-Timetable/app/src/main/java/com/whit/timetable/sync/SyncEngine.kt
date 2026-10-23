package com.whit.timetable.sync

import android.content.Context
import com.whit.timetable.data.AppPrefs
import com.whit.timetable.data.TimetableStore
import com.whit.timetable.net.WhitClient
import com.whit.timetable.net.WhitParser

/**
 * 同步逻辑集中在这里，前台和后台任务共用一套流程。
 * 用登录时保存的 JSESSIONID 直接拉课表；会话过期后只能回 WebView 重新登录
 * （芜湖教务登录带图形验证码，无法静默续期）。
 */
object SyncEngine {

    const val OK = 0
    const val NEED_LOGIN = 1
    const val ERROR = 2

    data class Result(val state: Int, val message: String)

    fun refresh(context: Context): Result {
        val prefs = AppPrefs(context)
        val store = TimetableStore(context)

        if (prefs.cookies.isEmpty()) {
            return Result(NEED_LOGIN, "还没登录教务")
        }

        val fetched = WhitClient.fetch(prefs)
        if (!fetched.ok) {
            if (fetched.needLogin) {
                prefs.needsLogin = true
                store.log("同步失败，需要重新登录：${fetched.message}")
                return Result(NEED_LOGIN, "登录已过期，请重新登录")
            }
            store.log("拉取课表失败：${fetched.message} ${fetched.body.take(500)}")
            return Result(ERROR, fetched.message)
        }

        val courses = WhitParser.parse(fetched.body)
        if (courses.isEmpty()) {
            store.log("解析结果为空，接口片段：\n${fetched.body.take(2000)}")
            return Result(ERROR, "教务返回的课表为空")
        }

        val merge = store.saveImported(courses)
        val note = mergeNote(merge)
        val now = System.currentTimeMillis()
        store.saveMeta(
            store.loadMeta().copy(
                termName = prefs.termName,
                startDate = prefs.termStart,
                updatedAt = now,
                syncState = "已更新 ${courses.size} 门课$note",
                studentName = prefs.studentName
            )
        )
        prefs.lastSync = now
        prefs.needsLogin = false
        store.log("同步成功，共 ${courses.size} 门课$note")
        return Result(OK, "已更新 ${courses.size} 门课$note")
    }

    /** 同步后自定义课程的冲突处理结果，拼进提示文案。 */
    fun mergeNote(merge: TimetableStore.MergeResult): String = when {
        merge.removed > 0 && merge.trimmed > 0 ->
            "；${merge.removed} 门自定义课与教务完全重叠已删除，${merge.trimmed} 门已裁掉重叠节次"
        merge.removed > 0 -> "；${merge.removed} 门自定义课与教务完全重叠已删除"
        merge.trimmed > 0 -> "；${merge.trimmed} 门自定义课与教务重叠，已裁掉重叠节次"
        else -> ""
    }
}

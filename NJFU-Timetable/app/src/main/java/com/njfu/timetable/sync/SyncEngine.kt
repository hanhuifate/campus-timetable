package com.njfu.timetable.sync

import android.content.Context
import com.njfu.timetable.data.AppPrefs
import com.njfu.timetable.data.TimetableStore
import com.njfu.timetable.net.JwxtClient
import com.njfu.timetable.net.JwxtParser

/**
 * 同步逻辑集中在这里，前台和后台任务共用一套流程。
 * 优先用登录时保存的 Cookie 直接拉课表，会话过期则用 CASTGC 静默续期。
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

        val client = JwxtClient(prefs.baseUrl)
        client.loadCookies(prefs.cookies)
        client.casCookie = prefs.casCookies

        val (xnm, xqm) = term(prefs.termStart)
        var fetched = client.fetchTimetable(xnm, xqm, prefs.username)

        if (!fetched.ok && fetched.needLogin) {
            store.log("教务会话失效，尝试用统一认证 Cookie 静默续期")
            if (client.refreshSession()) {
                fetched = client.fetchTimetable(xnm, xqm, prefs.username)
            }
        }

        if (!fetched.ok) {
            prefs.cookies = client.cookie
            if (fetched.needLogin) {
                prefs.needsLogin = true
                store.log("需要重新登录：${fetched.message}")
                return Result(NEED_LOGIN, "登录已过期，请重新登录")
            }
            store.log("拉取课表失败：${fetched.message}\n${fetched.body.take(1500)}")
            return Result(ERROR, fetched.message)
        }

        var courses = JwxtParser.parse(fetched.body)
        if (courses.isEmpty()) {
            // 学期参数可能与校历不一致，换一组再试
            for ((a, b) in alternatives(xnm, xqm)) {
                val retry = client.fetchTimetable(a, b, prefs.username)
                if (!retry.ok) continue
                val parsed = JwxtParser.parse(retry.body)
                if (parsed.isNotEmpty()) {
                    fetched = retry
                    courses = parsed
                    store.log("使用学期参数 $a-$b 取到课表")
                    break
                }
            }
        }
        if (courses.isEmpty()) {
            store.log("解析结果为空，接口片段：\n${fetched.body.take(3000)}")
            return Result(ERROR, "教务返回的课表为空")
        }

        val merge = store.saveImported(courses)
        val note = mergeNote(merge)
        store.saveMeta(
            store.loadMeta().copy(
                termName = prefs.termName,
                startDate = prefs.termStart,
                updatedAt = System.currentTimeMillis(),
                syncState = "已更新 ${courses.size} 门课$note",
                studentName = prefs.studentName
            )
        )
        prefs.cookies = client.cookie
        prefs.lastSync = System.currentTimeMillis()
        prefs.needsLogin = false
        store.log("同步成功，共 ${courses.size} 门课（学期 $xnm-$xqm）$note")
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

    /** 由第一周周一推算：秋季 xqm=3，春季 xqm=12。 */
    private fun term(startDate: String): Pair<String, String> {
        val month = startDate.substring(5, 7).toIntOrNull() ?: 9
        val year = startDate.substring(0, 4).toIntOrNull() ?: 2026
        return if (month in 1..7) {
            (year - 1).toString() to "12"
        } else {
            year.toString() to "3"
        }
    }

    private fun alternatives(xnm: String, xqm: String): List<Pair<String, String>> {
        val y = xnm.toIntOrNull() ?: return emptyList()
        return listOf(
            xnm to if (xqm == "3") "12" else "3",
            (y - 1).toString() to "12",
            (y - 1).toString() to "3"
        )
    }
}

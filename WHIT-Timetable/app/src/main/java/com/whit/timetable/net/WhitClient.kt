package com.whit.timetable.net

import com.whit.timetable.data.AppPrefs
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 芜湖金智 jwgl 课表接口。登录态是 WebView 里拿到的 JSESSIONID，
 * 服务端校验 Referer，必须带上课表页地址。
 */
object WhitClient {

    data class Response(
        val ok: Boolean,
        val needLogin: Boolean,
        val message: String,
        val body: String
    )

    fun fetch(prefs: AppPrefs): Response {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(prefs.baseUrl + "/jwgl/timetableController/timetable")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8"
            )
            conn.setRequestProperty("Referer", prefs.baseUrl + "/jwgl/jsp/app/kb.jsp")
            conn.setRequestProperty("Cookie", prefs.cookies)

            val body = "xh=" + URLEncoder.encode(prefs.username, "UTF-8") +
                "&qz=&mz=&kkxq=" + URLEncoder.encode(prefs.kkxq, "UTF-8") +
                "&type=student"
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            when {
                text.contains("\"list\"") ->
                    Response(true, false, "ok", text)

                text.contains("头文件信息错误") ->
                    Response(false, false, "教务拒绝了请求（Referer 校验）", text)

                text.contains("xlogin") || text.contains("authserver") ||
                    text.contains("统一身份认证") || code == 302 || code == 401 ->
                    Response(false, true, "登录已过期", text)

                else ->
                    Response(false, true, "教务没有返回课表（会话可能已失效）", text)
            }
        } catch (e: Exception) {
            Response(false, false, "网络错误：${e.message}", "")
        } finally {
            conn?.disconnect()
        }
    }
}

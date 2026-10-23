package com.njfu.timetable.net

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 新教务（jwglxt）+ 统一身份认证（CAS）。
 *
 * 登录本身交给 WebView 打开学校官方登录页完成（验证码、滑块都由官方页面负责），
 * 这里只做两件事：
 *  1. 复用 WebView 拿到的 Cookie 调用课表接口；
 *  2. 教务会话过期时，用统一认证的 CASTGC 静默换票，无需再让用户输密码。
 */
class JwxtClient(private val baseUrl: String) {

    data class Resp(val code: Int, val location: String, val body: String)

    data class Fetched(val ok: Boolean, val needLogin: Boolean, val message: String, val body: String)

    private val jar = LinkedHashMap<String, String>()

    var casCookie: String = ""

    val cookie: String
        get() = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun loadCookies(raw: String) {
        raw.split(";").forEach { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@forEach
            val name = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) jar[name] = value
        }
    }

    fun fetchTimetable(xnm: String, xqm: String, studentId: String): Fetched {
        val api = "$baseUrl/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151&su=" +
            URLEncoder.encode(studentId, "UTF-8")
        val form = "xnm=$xnm&xqm=$xqm&kzlx=ck&xsdm="
        val resp = try {
            open(
                api, "POST", form,
                mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$baseUrl/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                )
            )
        } catch (e: Exception) {
            return Fetched(false, false, "网络异常：${e.javaClass.simpleName}", "")
        }

        if (resp.code in 300..399) {
            val target = resolve(api, resp.location)
            return if (target.contains("authserver", true)) {
                Fetched(false, true, "登录已过期", "")
            } else {
                Fetched(false, false, "教务返回重定向：$target", "")
            }
        }
        if (resp.code != 200) {
            return Fetched(false, false, "教务返回 HTTP ${resp.code}", resp.body)
        }

        val body = resp.body.trim()
        if (body.startsWith("{")) return Fetched(true, false, "", body)
        if (body.contains("authserver", true) || body.contains("统一身份认证")) {
            return Fetched(false, true, "登录已过期", body)
        }
        return Fetched(false, false, "教务未返回课表数据", body)
    }

    /** 用 CASTGC 重新换一张 ST 票，拿回教务会话。 */
    fun refreshSession(): Boolean {
        if (!casCookie.contains("CASTGC", true)) return false
        return try {
            val service = "http://jwxt.njfu.edu.cn/"
            val casUrl = "https://uia.njfu.edu.cn/authserver/login?service=" +
                URLEncoder.encode(service, "UTF-8")
            val login = open(casUrl, "GET", null, emptyMap(), cookieOverride = casCookie)
            if (login.code !in 300..399 || login.location.isBlank()) return false
            var current = resolve(casUrl, login.location)
            if (!current.contains("jwxt.njfu.edu.cn", true)) return false

            var resp = open(current, "GET", null, emptyMap())
            var hops = 0
            while (resp.code in 300..399 && resp.location.isNotBlank() && hops < 4) {
                current = resolve(current, resp.location)
                resp = open(current, "GET", null, emptyMap())
                hops++
            }
            jar.keys.any { it.equals("JSESSIONID", ignoreCase = true) } && resp.code == 200
        } catch (e: Exception) {
            false
        }
    }

    private fun open(
        urlStr: String,
        method: String,
        form: String?,
        headers: Map<String, String>,
        cookieOverride: String? = null
    ): Resp {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.requestMethod = method
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        val ck = cookieOverride ?: cookie
        if (ck.isNotEmpty()) conn.setRequestProperty("Cookie", ck)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (form != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        }

        val code = conn.responseCode
        val location = conn.getHeaderField("Location") ?: ""
        mergeCookies(conn)
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val body = try {
            stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
        } catch (e: Exception) {
            ""
        }
        conn.disconnect()
        return Resp(code, location, body)
    }

    private fun mergeCookies(conn: HttpURLConnection) {
        conn.headerFields.entries.forEach { entry ->
            val key = entry.key ?: return@forEach
            if (!key.equals("Set-Cookie", ignoreCase = true)) return@forEach
            entry.value?.forEach { absorb(it) }
        }
    }

    private fun absorb(raw: String) {
        val first = raw.substringBefore(';')
        val idx = first.indexOf('=')
        if (idx <= 0) return
        val name = first.substring(0, idx).trim()
        val value = first.substring(idx + 1).trim()
        if (value.isEmpty() || value == "deleted" ||
            raw.contains("Max-Age=0", true) || raw.contains("1970", true)
        ) {
            jar.remove(name)
        } else {
            jar[name] = value
        }
    }

    private fun resolve(base: String, location: String): String {
        return try {
            URL(URL(base), location).toString()
        } catch (e: Exception) {
            location
        }
    }

    private companion object {
        const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

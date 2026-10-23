package com.njfu.timetable

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.njfu.timetable.data.AppPrefs
import com.njfu.timetable.data.Course
import com.njfu.timetable.data.TimetableStore
import com.njfu.timetable.net.JwxtParser
import com.njfu.timetable.sync.SyncEngine
import com.njfu.timetable.ui.Ui
import org.json.JSONObject

/**
 * 用学校官方统一身份认证页面完成登录。
 * 密码只交给学校页面；登录后直接在 WebView 内请求课表接口，
 * 这样 HttpOnly 会话 Cookie、重定向都由浏览器自己处理，不依赖抓 Cookie 的时机。
 */
class CasLoginActivity : Activity() {

    private lateinit var prefs: AppPrefs
    private lateinit var store: TimetableStore
    private lateinit var web: WebView
    private lateinit var hint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var completed = false
    private var enteredHome = false
    private var capturing = false

    private val ticker = object : Runnable {
        override fun run() {
            if (completed || isFinishing) return
            val url = web.url ?: ""
            if (url.contains(host()) && !maybeEnterHome(url)) {
                capturePage()
            }
            handler.postDelayed(this, 3000)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun onTimetable(json: String) {
            runOnUiThread { handleResult(json) }
        }

        /** 诊断用：把课表页原始 HTML 存下来，方便核对漏抓的课。 */
        @JavascriptInterface
        fun onHtml(html: String) {
            try {
                val dir = java.io.File(filesDir, "timetable").apply { mkdirs() }
                java.io.File(dir, "last_page.html").writeText(html)
                store.log("已保存课表页 HTML：${html.length} 字符")
            } catch (e: Exception) {
                store.log("保存 HTML 失败：${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        store = TimetableStore(this)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        buildUi()

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(Bridge(), "njfuBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                store.log("页面加载完成：$url")
                if (completed) return
                if (url.contains("authserver/login")) {
                    autoFill()
                } else if (url.contains(host())) {
                    if (!maybeEnterHome(url)) {
                        capturePage()
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                store.log("页面加载失败：${request.url} ${error.description}")
            }

        }
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                store.log("JS 控制台：${msg.message()} @${msg.lineNumber()}")
                return true
            }
        }
        web.loadUrl(prefs.baseUrl + "/")
        handler.postDelayed(ticker, 3000)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Ui.BG)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(6f), dp(10f), dp(6f))
        }
        bar.addView(
            Ui.text(this, "返回", 13f, Ui.ACCENT, gravity = Gravity.CENTER).apply {
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                setOnClickListener { finish() }
            }
        )
        bar.addView(
            Ui.text(this, "登录教务", 17f, Ui.TEXT_PRIMARY, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
            }
        )
        bar.addView(
            Ui.text(this, "完成", 13f, Ui.ACCENT, gravity = Gravity.CENTER).apply {
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                setOnClickListener {
                    this@CasLoginActivity.hint.text = "正在读取页面课表…"
                    capturePage()
                }
            }
        )
        root.addView(bar)

        hint = Ui.text(
            this,
            "账号密码会自动填入，点学校页面的「登录」即可；出现验证码时手动输入。",
            10.5f,
            Ui.TEXT_SECONDARY,
            gravity = Gravity.CENTER
        )
        hint.setPadding(dp(14f), dp(2f), dp(14f), dp(6f))
        root.addView(hint)

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
        }
        root.addView(web)
        setContentView(root)
    }

    /** 官方登录页提交时自己会读取这两个输入框，填入即可。 */
    private fun autoFill() {
        val user = prefs.username
        val pass = prefs.password
        if (user.isEmpty() && pass.isEmpty()) return
        // 电脑版与手机版的输入框 id 不同，两套都要兼容。
        val js = """
            (function(){
              var u = document.getElementById('username') || document.getElementById('mobileUsername');
              var p = document.getElementById('password') || document.getElementById('mobilePassword');
              if (u && !u.value) { u.value = ${JSONObject.quote(user)}; }
              if (p && !p.value) { p.value = ${JSONObject.quote(pass)}; }
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    /** 登录后学校会先落到“选课临时页”，这里自动跳回教务首页再抓课表。 */
    private fun maybeEnterHome(url: String): Boolean {
        if (enteredHome || completed) return false
        if (url.contains("xsrkxz")) {
            enteredHome = true
            store.log("检测到选课临时页，跳转教务首页")
            web.loadUrl(prefs.baseUrl + "/jsxsd/framework/xsMainV.jsp")
            return true
        }
        return false
    }

    /** 直接从教务首页的课表 DOM 里读取课程，避开接口鉴权。 */
    private fun capturePage() {
        if (completed || capturing) return
        capturing = true
        val js = """
            (function(){
              function findFrame(win, depth){
                if (depth > 3) return null;
                try { if ((win.location.href || '').indexOf('xsMainV_new') >= 0) return win; } catch (e) {}
                for (var f = 0; f < win.frames.length; f++) {
                  var r = findFrame(win.frames[f], depth + 1);
                  if (r) return r;
                }
                return null;
              }
              try {
                var w = findFrame(window, 0);
                if (!w) { njfuBridge.onTimetable('SCRAPE:NONE'); return; }
                var d = w.document;
                try { njfuBridge.onHtml(d.documentElement.outerHTML); } catch (e) {}
                var t = d.querySelectorAll('table')[0];
                if (!t) { njfuBridge.onTimetable('SCRAPE:NONE'); return; }
                var days = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '日': 7, '天': 7 };
                var list = [];
                var seen = {};
                for (var r = 1; r < t.rows.length; r++) {
                  for (var c = 1; c < t.rows[r].cells.length; c++) {
                    var td = t.rows[r].cells[c];
                    var boxes = td.querySelectorAll('.item-box');
                    if (!boxes.length) continue;
                    var tm = (td.innerText || '').match(/教师：\s*([^\s]+)/);
                    var teacher = tm ? tm[1] : '';
                    for (var b = 0; b < boxes.length; b++) {
                      // 一个格子可能叠了两门课（不同周次），每门课以 <p>课程名</p> 开头，
                      // 所以按 p 切段，而不是把整个格子当成一门课。
                      var ps = boxes[b].querySelectorAll('p');
                      for (var pi = 0; pi < ps.length; pi++) {
                        var p = ps[pi];
                        var name = (p.innerText || p.textContent || '').trim();
                        if (!name) continue;
                        var seg = '';
                        var n = p.nextSibling;
                        while (n && !(n.nodeType === 1 && n.tagName === 'P')) {
                          if (n.nodeType === 1) seg += ' ' + (n.innerText || n.textContent || '');
                          n = n.nextSibling;
                        }
                        seg = seg.replace(/\s+/g, ' ');
                        var wm = seg.match(/第([0-9,~\-]+)周/);
                        if (!wm) continue;
                        var sm = seg.match(/[0-9]{2}(?:~[0-9]{2})+节/);
                        var dm = seg.match(/星期([一二三四五六日天])/);
                        var rm = seg.match(/([^\s]*校区[^\s]*?)(?=第[0-9]|$)/);
                        var room = rm ? rm[1] : '';
                        var weeks = wm[1].split(',').map(function(x){ return x + '周'; }).join(',');
                        var key = name + '|' + (sm ? sm[0] : '') + '|' + weeks +
                                  '|' + (dm ? dm[1] : c) + '|' + room;
                        if (seen[key]) continue;
                        seen[key] = 1;
                        list.push({
                          kcmc: name,
                          xm: teacher,
                          cdmc: room,
                          xqj: dm ? days[dm[1]] : c,
                          jcs: sm ? sm[0] : '',
                          zcd: weeks
                        });
                      }
                    }
                  }
                }
                if (!list.length) { njfuBridge.onTimetable('SCRAPE:NONE'); return; }
                njfuBridge.onTimetable(JSON.stringify({ kbList: list }));
              } catch (e) { njfuBridge.onTimetable('SCRAPE:NONE'); }
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun handleResult(json: String) {
        capturing = false
        if (completed) return
        if (json == "SCRAPE:NONE") {
            store.log("页面暂无课表，继续等待")
            hint.text = "已进入教务首页，正在读取课表…"
            return
        }
        store.log(
            "WebView 课表接口返回：长度=${json.length}，开头=" +
                json.take(160).replace("\n", " ")
        )
        try {
            java.io.File(java.io.File(filesDir, "timetable"), "last_scrape.json")
                .writeText(json)
        } catch (e: Exception) {
            store.log("保存抓取结果失败：${e.message}")
        }

        val text = json.trim().removePrefix("\uFEFF")
        if (text.startsWith("{") || text.contains("\"kbList\"")) {
            val courses = JwxtParser.parse(text)
            store.log("解析结果：${courses.size} 门课")
            if (courses.isNotEmpty() || text.contains("\"kbList\"")) {
                saveAndFinish(courses)
                return
            }
        }

        hint.text = when {
            json.startsWith("ERR:") -> "网络异常：${json.removePrefix("ERR:").take(40)}"
            json.contains("authserver", true) || json.contains("统一身份认证") ->
                "App 内还没有登录会话，请回到登录页重新输入密码"
            else -> "尚未读取到课表，请等页面显示课表后点「完成」"
        }
    }

    private fun saveAndFinish(courses: List<Course>) {
        if (completed) return
        completed = true
        val cookie = jwxtCookies()
        prefs.cookies = cookie
        prefs.casCookies = cookieFor(
            "https://uia.njfu.edu.cn/",
            "https://uia.njfu.edu.cn/authserver/"
        )
        prefs.needsLogin = false

        var note = ""
        if (courses.isNotEmpty()) {
            note = SyncEngine.mergeNote(store.saveImported(courses))
            store.saveMeta(
                store.loadMeta().copy(
                    termName = prefs.termName,
                    startDate = prefs.termStart,
                    updatedAt = System.currentTimeMillis(),
                    syncState = "已更新 ${courses.size} 门课$note",
                    studentName = prefs.studentName
                )
            )
            prefs.lastSync = System.currentTimeMillis()
        }

        val names = cookie.split(";")
            .map { it.substringBefore("=").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(",")
        store.log("登录成功，保存 ${courses.size} 门课；Cookie 名称=$names")

        Toast.makeText(this, "登录成功，已导入 ${courses.size} 门课$note", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK, Intent().putExtra("count", courses.size))
        finish()
    }

    private fun jwxtCookies(): String {
        val base = prefs.baseUrl
        val plain = base.replace("https://", "http://")
        return cookieFor(
            "$base/", "$plain/",
            "$base/jwglxt/", "$plain/jwglxt/",
            "$base/jsxsd/", "$plain/jsxsd/"
        )
    }

    /**
     * 会话 Cookie 可能挂在 /jwglxt 或 /authserver 路径下，
     * 只取根路径会漏掉，所以按路径逐个取再合并。
     */
    private fun cookieFor(vararg urls: String): String {
        val cm = CookieManager.getInstance()
        val map = LinkedHashMap<String, String>()
        urls.forEach { url ->
            cm.getCookie(url).orEmpty().split(";").forEach { part ->
                val idx = part.indexOf('=')
                if (idx > 0) {
                    val name = part.substring(0, idx).trim()
                    val value = part.substring(idx + 1).trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) map[name] = value
                }
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun host(): String = prefs.baseUrl
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            web.stopLoading()
            web.removeJavascriptInterface("njfuBridge")
            web.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Float): Int = Ui.px(this, value)
}

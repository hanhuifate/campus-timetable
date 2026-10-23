package com.whit.timetable

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
import com.whit.timetable.data.AppPrefs
import com.whit.timetable.data.TimetableMeta
import com.whit.timetable.data.TimetableStore
import com.whit.timetable.net.WhitParser
import com.whit.timetable.ui.Ui
import org.json.JSONArray
import org.json.JSONObject

/**
 * 芜湖教务是金智 jwgl 体系：登录页带图形验证码，
 * 所以账号密码自动填入，验证码由用户在学校页面上手动输入。
 *
 * 课表页结构尚未落地成解析规则，这一版先把登录后各页面的
 * 表格结构导出到本机（files/timetable/page-*.html），再据此写死解析。
 */
class CasLoginActivity : Activity() {

    private lateinit var prefs: AppPrefs
    private lateinit var store: TimetableStore
    private lateinit var web: WebView
    private lateinit var hint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var capturing = false
    private var lastDumpedUrl = ""
    private var enteredKb = false
    private var completed = false

    private val ticker = object : Runnable {
        override fun run() {
            if (completed || isFinishing) return
            val url = web.url ?: ""
            if (url.contains(host()) && !isLoginPage(url) && url != lastDumpedUrl) {
                capturePage()
            }
            handler.postDelayed(this, 3000)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun onPageInfo(json: String) {
            runOnUiThread { handlePageInfo(json) }
        }

        @JavascriptInterface
        fun onKb(json: String) {
            runOnUiThread { handleKb(json) }
        }

        @JavascriptInterface
        fun onSchedule(json: String) {
            runOnUiThread { handleSchedule(json) }
        }

        @JavascriptInterface
        fun onKkxq(value: String) {
            prefs.kkxq = value
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

        web.addJavascriptInterface(Bridge(), "whitBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                store.log("页面加载完成：$url")
                if (completed) return
                if (isLoginPage(url)) {
                    autoFill()
                } else if (url.contains(host())) {
                    if (url.contains("index.jsp") && !enteredKb) {
                        enteredKb = true
                        store.log("已进入教务首页，自动打开课表页")
                        web.loadUrl(prefs.baseUrl + "/jwgl/jsp/app/kb.jsp")
                    } else {
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
        web.loadUrl(startUrl())
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
                    this@CasLoginActivity.hint.text = "正在记录当前页面…"
                    lastDumpedUrl = ""
                    capturePage()
                }
            }
        )
        root.addView(bar)

        hint = Ui.text(
            this,
            "账号密码会自动填入，图形验证码请自己输入后点「登录」；进入课表页后点右上角「完成」。",
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

    /** 金智 jwgl 登录页：userCode / nvcpwd，验证码留给用户。 */
    private fun autoFill() {
        val user = prefs.username
        val pass = prefs.password
        if (user.isEmpty() && pass.isEmpty()) return
        val js = """
            (function(){
              var u = document.getElementById('userCode') || document.getElementById('username');
              var p = document.getElementById('nvcpwd') || document.getElementById('password');
              if (u && !u.value) { u.value = ${JSONObject.quote(user)}; }
              if (p && !p.value) { p.value = ${JSONObject.quote(pass)}; }
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    /**
     * 枚举所有 frame，记录每张表的大小与文本片段；
     * 含“星期/节次”的页面额外把完整 HTML 落盘，供后续写解析规则。
     */
    private fun capturePage() {
        if (capturing) return
        capturing = true
        val js = """
            (function(){
              function walk(win, depth, out){
                if (depth > 3) return;
                var item = { url: '', tables: [], html: '', error: '' };
                try { item.url = win.location.href || ''; } catch (e) { item.url = 'cross-origin'; }
                try {
                  var doc = win.document;
                  var tables = doc.querySelectorAll('table');
                  for (var i = 0; i < tables.length; i++) {
                    var t = tables[i];
                    var rows = t.rows ? t.rows.length : 0;
                    var cols = rows ? t.rows[0].cells.length : 0;
                    var text = (t.innerText || '').replace(/\s+/g, ' ').trim();
                    item.tables.push({ rows: rows, cols: cols, text: text.slice(0, 400) });
                  }
                  var body = (doc.body ? (doc.body.innerText || '') : '').replace(/\s+/g, ' ');
                  if (tables.length > 0) {
                    item.html = doc.documentElement.outerHTML.slice(0, 300000);
                    var big = tables[0];
                    for (var k = 1; k < tables.length; k++) {
                      if (tables[k].rows.length > big.rows.length) big = tables[k];
                    }
                    item.table = big.outerHTML.slice(0, 200000);
                  }
                } catch (e) { item.error = String(e); }
                out.push(item);
                try {
                  for (var f = 0; f < win.frames.length; f++) walk(win.frames[f], depth + 1, out);
                } catch (e) {}
              }
              function findKb(win, depth){
                if (depth > 3) return null;
                try { if ((win.location.href || '').indexOf('kb.jsp') >= 0) return win; } catch (e) {}
                try {
                  for (var f = 0; f < win.frames.length; f++) {
                    var r = findKb(win.frames[f], depth + 1);
                    if (r) return r;
                  }
                } catch (e) {}
                return null;
              }
              try {
                var out = [];
                walk(window, 0, out);
                whitBridge.onPageInfo(JSON.stringify(out));
              } catch (e) { whitBridge.onPageInfo('ERR:' + e); }
              try {
                var kw = findKb(window, 0);
                if (kw) {
                  var zc = null;
                  try { zc = JSON.parse(kw.sessionStorage.getItem('_global.zcInfo')); } catch (e) {}
                  var kkxq = (zc && zc.KKXQ_T) ? String(zc.KKXQ_T) : '';
                  whitBridge.onKkxq(kkxq);
                  var base = kw.location.origin;
                  var body = 'xh=' + encodeURIComponent(${JSONObject.quote(prefs.username)}) +
                      '&qz=&mz=&kkxq=' + encodeURIComponent(kkxq) + '&type=student';
                  var xhr = new kw.XMLHttpRequest();
                  xhr.open('POST', base + '/jwgl/timetableController/timetable', true);
                  xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8');
                  xhr.onload = function(){ whitBridge.onKb(xhr.responseText); };
                  xhr.onerror = function(){ whitBridge.onKb('ERR:xhr'); };
                  xhr.send(body);

                  var x2 = new kw.XMLHttpRequest();
                  x2.open('POST', base + '/jwgl/baseInfoController/getScheduleTime', true);
                  x2.onload = function(){ whitBridge.onSchedule(x2.responseText); };
                  x2.send(null);
                }
              } catch (e) { whitBridge.onKb('ERR:' + e); }
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun handlePageInfo(json: String) {
        capturing = false
        if (json.startsWith("ERR:")) {
            store.log("页面结构读取失败：${json.take(200)}")
            hint.text = "页面结构读取失败，请稍后点「完成」重试"
            return
        }
        lastDumpedUrl = web.url ?: ""
        val frames = try {
            JSONArray(json)
        } catch (e: Exception) {
            store.log("页面结构无法解析：${json.take(300)}")
            return
        }

        val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.CHINA)
            .format(java.util.Date())
        var dumped = 0
        val summary = StringBuilder()
        for (i in 0 until frames.length()) {
            val f = frames.optJSONObject(i) ?: continue
            val url = f.optString("url")
            val tables = f.optJSONArray("tables") ?: JSONArray()
            val html = f.optString("html")
            summary.append("\n  [frame $i] $url 表格=${tables.length()}")
            for (t in 0 until tables.length()) {
                val tb = tables.optJSONObject(t) ?: continue
                summary.append(
                    "\n    #$t ${tb.optInt("rows")}x${tb.optInt("cols")} " +
                        tb.optString("text").take(120)
                )
            }
            if (html.isNotEmpty()) {
                store.saveDump("page-$stamp-$i.html", html)
                dumped++
            }
            val table = f.optString("table")
            if (table.isNotEmpty()) {
                store.saveDump("table-$stamp-$i.html", table)
            }
        }
        store.log("页面结构（$stamp）：$summary")

        hint.text = if (dumped > 0) {
            "已记录当前页面（$dumped 个），可继续打开课表页"
        } else {
            "当前页没有课表，请在教务菜单里打开课表再点「完成」"
        }
    }

    private fun handleKb(json: String) {
        val stamp = stamp()
        store.saveDump("kb-$stamp.json", json)
        store.log("课表接口返回：长度=${json.length}，开头=${json.take(300)}")
        saveCookies()
        if (json.startsWith("ERR:")) {
            hint.text = "课表接口调用失败：${json.take(80)}"
            return
        }
        prefs.needsLogin = false

        val courses = WhitParser.parse(json)
        store.log("解析结果：${courses.size} 门课")
        if (courses.isEmpty()) {
            hint.text = "接口有数据但没解析出课程，请把诊断日志发我"
            return
        }
        store.saveCourses(courses)
        val now = System.currentTimeMillis()
        prefs.lastSync = now
        store.saveMeta(
            TimetableMeta(
                termName = prefs.termName,
                startDate = prefs.termStart,
                updatedAt = now,
                syncState = "已更新 ${courses.size} 门课",
                studentName = prefs.username
            )
        )
        hint.text = "已导入 ${courses.size} 门课"
        completed = true
        setResult(RESULT_OK, Intent().putExtra("count", courses.size))
        finish()
    }

    private fun handleSchedule(json: String) {
        store.log("节次时间接口返回：长度=${json.length}，开头=${json.take(600)}")
        store.saveDump("schedule-${stamp()}.json", json)
    }

    private fun saveCookies() {
        try {
            prefs.cookies = cookieFor(
                prefs.baseUrl + "/",
                prefs.baseUrl + "/jwgl/",
                prefs.baseUrl + "/jwgl/jsp/",
                prefs.baseUrl + "/jwgl/jsp/app/"
            )
            store.log("已保存教务 Cookie：${prefs.cookies.take(160)}")
        } catch (e: Exception) {
            store.log("Cookie 保存失败：${e.message}")
        }
    }

    private fun cookieFor(vararg urls: String): String {
        val cm = CookieManager.getInstance()
        val sb = StringBuilder()
        for (u in urls) {
            val c = cm.getCookie(u) ?: continue
            if (c.isNotBlank()) sb.append(c).append("; ")
        }
        return sb.toString().trimEnd(';', ' ')
    }

    private fun stamp(): String =
        java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.CHINA)
            .format(java.util.Date())

    private fun startUrl(): String = prefs.baseUrl + "/jwgl/xlogin.jsp"

    private fun isLoginPage(url: String): Boolean =
        url.contains("xlogin", true) || url.contains("authserver", true)

    private fun host(): String = prefs.baseUrl
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            web.stopLoading()
            web.removeJavascriptInterface("whitBridge")
            web.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Float): Int = Ui.px(this, value)
}

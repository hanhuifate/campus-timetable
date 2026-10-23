package com.whit.timetable

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.whit.timetable.data.AppPrefs
import com.whit.timetable.data.TimetableStore
import com.whit.timetable.ui.Ui

class SettingsActivity : Activity() {

    private lateinit var prefs: AppPrefs
    private lateinit var store: TimetableStore
    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var termField: EditText
    private lateinit var startField: EditText
    private lateinit var weekendSwitch: Switch
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        store = TimetableStore(this)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        buildUi()
        fillFields()
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
            setPadding(dp(8f), dp(6f), dp(14f), dp(6f))
        }
        val back = Ui.text(this, "返回", 13f, Ui.ACCENT, gravity = Gravity.CENTER).apply {
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setOnClickListener { finish() }
        }
        val title = Ui.text(this, "设置", 17f, Ui.TEXT_PRIMARY, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        }
        bar.addView(back)
        bar.addView(title)
        bar.addView(Ui.text(this, "     ", 13f, Ui.TEXT_PRIMARY))
        root.addView(bar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(4f), dp(14f), dp(20f))
        }

        // 账号卡片
        val accountCard = card()
        accountCard.addView(sectionTitle("教务账号"))
        userField = input("学号", prefs.username, false)
        passField = input("密码", prefs.password, true)
        accountCard.addView(fieldLabel("学号"))
        accountCard.addView(userField)
        accountCard.addView(fieldLabel("密码（仅存本机加密）"))
        accountCard.addView(passField)
        val accountHint = Ui.text(
            this,
            "登录在学校官方教务页完成：账号密码自动填入，图形验证码需手动输入；教务地址已固定为 jwxt.whit.edu.cn。",
            10.5f,
            Ui.TEXT_SECONDARY
        )
        accountHint.setPadding(0, dp(8f), 0, 0)
        accountCard.addView(accountHint)
        content.addView(accountCard)

        // 学期卡片
        val termCard = card()
        termCard.addView(sectionTitle("学期与周次"))
        termField = input("如 2026-2027 秋季学期", prefs.termName, false)
        startField = input("yyyy-MM-dd", prefs.termStart, false)
        termCard.addView(fieldLabel("学期名称"))
        termCard.addView(termField)
        termCard.addView(fieldLabel("第一周周一（按校历）"))
        termCard.addView(startField)
        weekendSwitch = Switch(this).apply {
            text = "显示周六周日"
            textSize = 13f
            setTextColor(Ui.TEXT_PRIMARY)
            isChecked = prefs.dayCount == 7
            setPadding(0, dp(8f), 0, 0)
        }
        termCard.addView(weekendSwitch)
        val hint = Ui.text(
            this,
            "按学校校历填写第一周周一；不确定时先用默认值 2026-08-31",
            10.5f,
            Ui.TEXT_SECONDARY
        )
        hint.setPadding(0, dp(6f), 0, 0)
        termCard.addView(hint)
        content.addView(termCard)

        // 操作按钮
        val sync = primaryButton("保存并登录同步")
        sync.setOnClickListener {
            if (saveFields()) startActivityForResult(Intent(this, CasLoginActivity::class.java), REQ_LOGIN)
        }
        content.addView(sync)

        val save = ghostButton("仅保存设置")
        save.setOnClickListener {
            if (saveFields()) status.text = "设置已保存"
        }
        content.addView(save)

        val diag = ghostButton("查看诊断日志（抓取失败时用）")
        diag.setOnClickListener { showDiagnostics() }
        content.addView(diag)

        status = Ui.text(this, "", 11.5f, Ui.TEXT_SECONDARY, gravity = Gravity.CENTER)
        status.setPadding(0, dp(12f), 0, 0)
        content.addView(status)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK) {
            val count = data?.getIntExtra("count", 0) ?: 0
            if (count > 0) {
                status.text = "同步完成，共 $count 门课"
                Toast.makeText(this, "课表已更新", Toast.LENGTH_SHORT).show()
            } else {
                status.text = "登录成功，但还没解析到课表"
            }
        }
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@SettingsActivity, Ui.SURFACE, 14f)
            setPadding(dp(13f), dp(13f), dp(13f), dp(13f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10f)
                bottomMargin = dp(4f)
            }
        }
    }

    private fun sectionTitle(text: String): TextView = Ui.text(this, text, 13.5f, Ui.TEXT_PRIMARY, bold = true)
        .apply { setPadding(0, 0, 0, dp(6f)) }

    private fun fieldLabel(text: String): TextView = Ui.text(this, text, 11f, Ui.TEXT_SECONDARY).apply {
        setPadding(0, dp(9f), 0, dp(3f))
    }

    private fun input(hint: String, value: String, password: Boolean): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 14f
            setTextColor(Ui.TEXT_PRIMARY)
            setHintTextColor(0xFF9AA1AC.toInt())
            background = Ui.rounded(this@SettingsActivity, 0xFFF5F6F8.toInt(), 10f)
            setPadding(dp(11f), dp(9f), dp(11f), dp(9f))
            maxLines = 1
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun primaryButton(label: String): TextView {
        return Ui.text(this, label, 14f, 0xFFFFFFFF.toInt(), bold = true, gravity = Gravity.CENTER).apply {
            background = Ui.rounded(this@SettingsActivity, Ui.ACCENT, 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46f)
            ).apply { topMargin = dp(16f) }
            isClickable = true
        }
    }

    private fun ghostButton(label: String): TextView {
        return Ui.text(this, label, 13f, Ui.ACCENT, gravity = Gravity.CENTER).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42f)
            ).apply { topMargin = dp(6f) }
            isClickable = true
        }
    }

    private fun fillFields() {
        userField.setText(prefs.username)
        passField.setText(prefs.password)
        termField.setText(prefs.termName)
        startField.setText(prefs.termStart)
    }

    private fun saveFields(): Boolean {
        val start = startField.text.toString().trim()
        if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(start)) {
            status.text = "学期起始日期格式应为 yyyy-MM-dd"
            return false
        }
        prefs.username = userField.text.toString()
        prefs.password = passField.text.toString()
        prefs.termName = termField.text.toString().ifBlank { "本学期" }
        prefs.termStart = start
        prefs.dayCount = if (weekendSwitch.isChecked) 7 else 5
        return true
    }

    private fun showDiagnostics() {
        val box = ScrollView(this).apply {
            setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
        }
        val text = Ui.text(this, store.readDiag(), 10f, Ui.TEXT_SECONDARY).apply {
            typeface = Typeface.MONOSPACE
        }
        box.addView(text)
        AlertDialog.Builder(this)
            .setTitle("诊断日志")
            .setView(box)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空") { _, _ -> store.clearDiag() }
            .show()
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread {
            if (!isFinishing) block()
        }
    }

    private fun dp(value: Float): Int = Ui.px(this, value)

    private companion object {
        const val REQ_LOGIN = 21
    }
}

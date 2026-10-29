package com.whit.timetable

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.whit.timetable.data.IcsExporter
import com.whit.timetable.data.TimetableStore
import com.whit.timetable.reminder.ReminderNotifier
import com.whit.timetable.reminder.ReminderScheduler
import com.whit.timetable.ui.Ui

class SettingsActivity : Activity() {

    private lateinit var prefs: AppPrefs
    private lateinit var store: TimetableStore
    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var termField: EditText
    private lateinit var startField: EditText
    private lateinit var weekendSwitch: Switch
    private lateinit var remindSwitch: Switch
    private lateinit var leadField: EditText
    private lateinit var status: TextView
    private lateinit var permStatus: TextView
    private lateinit var alarmAction: TextView
    private var selectedTone = ReminderNotifier.TONE_VIBRATE
    private var selectedRingSeconds = 6

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
            setOnClickListener { saveAndFinish() }
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

        // 上课提醒卡片：提前分钟数可改，默认只震动，可切提示音
        val remindCard = card()
        remindCard.addView(sectionTitle("上课提醒"))
        remindSwitch = Switch(this).apply {
            text = "上课前用状态栏提醒"
            textSize = 13f
            setTextColor(Ui.TEXT_PRIMARY)
            isChecked = prefs.remindEnabled
        }
        remindCard.addView(remindSwitch)
        remindCard.addView(fieldLabel("提前多少分钟（1-180，默认 25）"))
        leadField = input("25", prefs.remindMinutes.toString(), false)
        leadField.inputType = InputType.TYPE_CLASS_NUMBER
        remindCard.addView(leadField)
        remindCard.addView(fieldLabel("提醒方式（默认仅震动）"))
        selectedTone = prefs.remindTone.takeIf { it in ReminderNotifier.TONES }
            ?: ReminderNotifier.TONE_VIBRATE
        val toneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var syncRingRow: () -> Unit = {}
        val toneChips = ArrayList<TextView>()
        ReminderNotifier.TONES.forEach { tone ->
            val chip = Ui.text(
                this,
                ReminderNotifier.label(tone),
                11.5f,
                Ui.TEXT_SECONDARY,
                bold = true,
                gravity = Gravity.CENTER
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply {
                    marginEnd = dp(4f)
                }
                setOnClickListener {
                    selectedTone = tone
                    toneChips.forEach { view ->
                        styleToneChip(view, view.text.toString() == ReminderNotifier.label(selectedTone))
                    }
                    syncRingRow()
                }
            }
            toneChips.add(chip)
            toneRow.addView(chip)
        }
        toneChips.forEach { styleToneChip(it, it.text.toString() == ReminderNotifier.label(selectedTone)) }
        remindCard.addView(toneRow)

        // 闹钟音 / 铃声较长，单独给个响铃时长，默认 6 秒；仅震动、通知音不需要这一行
        val ringLabel = fieldLabel("响铃时长（闹钟音 / 铃声，默认 6 秒）")
        remindCard.addView(ringLabel)
        val ringOptions = listOf(2, 4, 6, 10, 15)
        selectedRingSeconds = prefs.ringSeconds.takeIf { it in ringOptions } ?: 6
        val ringRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ringChips = ArrayList<TextView>()
        ringOptions.forEach { seconds ->
            val chip = Ui.text(
                this,
                "${seconds}s",
                11.5f,
                Ui.TEXT_SECONDARY,
                bold = true,
                gravity = Gravity.CENTER
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply {
                    marginEnd = dp(4f)
                }
                setOnClickListener {
                    selectedRingSeconds = seconds
                    ringChips.forEach { view -> styleToneChip(view, view.text.toString() == "${seconds}s") }
                }
            }
            ringChips.add(chip)
            ringRow.addView(chip)
        }
        ringChips.forEach { styleToneChip(it, it.text.toString() == "${selectedRingSeconds}s") }
        remindCard.addView(ringRow)

        syncRingRow = {
            val show = ReminderNotifier.popsUp(selectedTone)
            ringLabel.visibility = if (show) View.VISIBLE else View.GONE
            ringRow.visibility = if (show) View.VISIBLE else View.GONE
        }
        syncRingRow()

        val preview = Ui.text(
            this,
            "试听当前提醒效果",
            12f,
            Ui.ACCENT,
            bold = true,
            gravity = Gravity.CENTER
        ).apply {
            background = Ui.rounded(this@SettingsActivity, 0xFFE6ECFA.toInt(), 10f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40f)
            ).apply { topMargin = dp(10f) }
            setOnClickListener { previewReminder() }
        }
        remindCard.addView(preview)
        val remindHint = Ui.text(
            this,
            "按作息表计算上课时间；同一天连续的同名课程只在第一段提醒。提醒由系统闹钟触发，App 没打开也能收到。选闹钟音或铃声时通知会一直挂着，点「停止」、按电源键或音量键都能关掉。",
            10.5f,
            Ui.TEXT_SECONDARY
        )
        remindHint.setPadding(0, dp(8f), 0, 0)
        remindCard.addView(remindHint)
        content.addView(remindCard)

        // 权限引导：小米 / 澎湃等机型默认把提醒收进「更多通知」，需要用户在系统设置里打开
        val permCard = card()
        permCard.addView(sectionTitle("通知与提醒权限"))
        permStatus = Ui.text(this, "", 11.5f, Ui.TEXT_SECONDARY)
        permCard.addView(permStatus)
        val notifAction = Ui.text(
            this,
            "打开通知设置",
            12.5f,
            Ui.ACCENT,
            bold = true,
            gravity = Gravity.CENTER
        ).apply {
            background = Ui.rounded(this@SettingsActivity, 0xFFE6ECFA.toInt(), 10f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40f)
            ).apply { topMargin = dp(9f) }
            setOnClickListener { openNotificationSettings() }
        }
        permCard.addView(notifAction)
        val permHint = Ui.text(
            this,
            "在「上课提醒」里打开「允许通知」和「悬浮通知」，提醒才会以横幅弹出；只开允许通知时通知会收进「更多通知」。",
            10.5f,
            Ui.TEXT_SECONDARY
        )
        permHint.setPadding(0, dp(8f), 0, 0)
        permCard.addView(permHint)
        alarmAction = Ui.text(
            this,
            "打开「闹钟与提醒」权限",
            12.5f,
            Ui.ACCENT,
            bold = true,
            gravity = Gravity.CENTER
        ).apply {
            background = Ui.rounded(this@SettingsActivity, 0xFFE6ECFA.toInt(), 10f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40f)
            ).apply { topMargin = dp(9f) }
            setOnClickListener { openExactAlarmSettings() }
        }
        permCard.addView(alarmAction)
        val bgAction = Ui.text(
            this,
            "打开后台运行设置",
            12.5f,
            Ui.ACCENT,
            bold = true,
            gravity = Gravity.CENTER
        ).apply {
            background = Ui.rounded(this@SettingsActivity, 0xFFE6ECFA.toInt(), 10f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40f)
            ).apply { topMargin = dp(9f) }
            setOnClickListener { openBackgroundSettings() }
        }
        permCard.addView(bgAction)
        val bgHint = Ui.text(
            this,
            "在「自启动」里允许本应用、省电策略设为「无限制」，清理后台后闹钟才能稳定触发；手动「强行停止」应用时系统会清掉所有闹钟，重新打开应用即可恢复。",
            10.5f,
            Ui.TEXT_SECONDARY
        )
        bgHint.setPadding(0, dp(8f), 0, 0)
        permCard.addView(bgHint)
        content.addView(permCard)

        // iPhone / 其他设备：导出 ics 交给系统日历提醒
        val exportCard = card()
        exportCard.addView(sectionTitle("导出到系统日历（iPhone 可用）"))
        val exportHint = Ui.text(
            this,
            "生成 .ics 文件，在 iPhone 上用邮件、微信或「文件」打开后导入「日历」，之后由系统日历按周提醒，不需要 App 常驻后台。课表变动后重新导出并删掉旧的日历事件即可。",
            10.5f,
            Ui.TEXT_SECONDARY
        )
        exportHint.setPadding(0, 0, 0, 0)
        exportCard.addView(exportHint)
        val export = ghostButton("导出课表 .ics")
        export.setOnClickListener {
            if (!IcsExporter.share(this)) {
                status.text = "还没有课表，先同步或添加课程后再导出"
            }
        }
        exportCard.addView(export)
        content.addView(exportCard)

        // 操作按钮
        val sync = primaryButton("保存并登录同步")
        sync.setOnClickListener {
            if (saveFields()) startActivityForResult(Intent(this, CasLoginActivity::class.java), REQ_LOGIN)
        }
        content.addView(sync)

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

    override fun onResume() {
        super.onResume()
        // 从系统授权页返回后，用新的权限状态重排一次
        if (this::leadField.isInitialized) {
            ReminderScheduler.schedule(this)
            refreshPermissionStatus()
        }
    }

    /** 展示通知、提醒分类、精确闹钟的实际授权状态，并隐式建好渠道。 */
    private fun refreshPermissionStatus() {
        if (!this::permStatus.isInitialized) return
        // 先建渠道，用户才能在系统设置里看到「上课提醒」分类并打开悬浮通知
        ReminderNotifier.ensureChannel(this)
        val manager = getSystemService(NotificationManager::class.java)
        val enabled = manager.areNotificationsEnabled()
        val channel = manager.getNotificationChannel(ReminderNotifier.CHANNEL_ALERT)
        permStatus.text = when {
            !enabled -> "通知：已关闭，提醒不会显示"
            channel == null || channel.importance >= NotificationManager.IMPORTANCE_HIGH ->
                "通知：已允许。若系统仍不弹横幅，请把「上课提醒」的悬浮通知打开。"
            channel.importance >= NotificationManager.IMPORTANCE_DEFAULT ->
                "通知：已允许，但横幅被关闭，提醒会收进「更多通知」"
            else -> "通知：该提醒分类被系统关闭了"
        }

        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        alarmAction.text = if (exact) "精确闹钟：已开启" else "打开「闹钟与提醒」权限"
        alarmAction.setTextColor(if (exact) Ui.TEXT_SECONDARY else Ui.ACCENT)
        alarmAction.background = Ui.rounded(
            this,
            if (exact) 0xFFF1F3F6.toInt() else 0xFFE6ECFA.toInt(),
            10f
        )
    }

    /** 跳到本应用的「上课提醒」通知分类，那里同时有「允许通知」和「悬浮通知」两个开关。 */
    private fun openNotificationSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, ReminderNotifier.CHANNEL_ALERT),
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        Toast.makeText(
            this,
            "请到「设置 → 应用 → ${getString(R.string.app_name)} → 通知」手动打开",
            Toast.LENGTH_LONG
        ).show()
    }

    /** 自启动 / 省电策略页：清理后台后闹钟是否还能触发，取决于这两项。 */
    private fun openBackgroundSettings() {
        val label = getString(R.string.app_name)
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            ).putExtra("package_name", packageName).putExtra("package_label", label),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        Toast.makeText(this, "请到系统设置的「应用管理」里允许自启动并取消省电限制", Toast.LENGTH_LONG).show()
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            Toast.makeText(this, "请到「设置 → 应用 → 特殊权限 → 闹钟与提醒」手动打开", Toast.LENGTH_LONG).show()
        }
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
        val lead = leadField.text.toString().trim().toIntOrNull()
        if (lead == null || lead !in 1..180) {
            status.text = "提前分钟数请填 1-180 的数字"
            return false
        }
        prefs.username = userField.text.toString()
        prefs.password = passField.text.toString()
        prefs.termName = termField.text.toString().ifBlank { "本学期" }
        prefs.termStart = start
        prefs.dayCount = if (weekendSwitch.isChecked) 7 else 5
        prefs.remindEnabled = remindSwitch.isChecked
        prefs.remindMinutes = lead
        prefs.remindTone = selectedTone
        prefs.ringSeconds = selectedRingSeconds
        ReminderScheduler.schedule(this)
        return true
    }

    /** 返回时自动保存，避免改完设置忘记保存。 */
    private fun saveAndFinish() {
        if (!saveFields()) return
        if (askExactAlarm()) return
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        saveAndFinish()
    }

    /** 试听：按当前选中的提醒方式发一条示例通知。 */
    private fun previewReminder() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            status.text = "请先在下方「通知与提醒权限」里打开通知，否则收不到提醒"
            return
        }
        val minutes = leadField.text.toString().trim().toIntOrNull()?.coerceIn(1, 180)
            ?: prefs.remindMinutes
        ReminderNotifier.preview(this, minutes, selectedTone)
        status.text = "已发送示例提醒：${ReminderNotifier.label(selectedTone)}"
    }

    private fun styleToneChip(chip: TextView, selected: Boolean) {
        chip.setTextColor(if (selected) 0xFFFFFFFF.toInt() else Ui.TEXT_SECONDARY)
        chip.background = Ui.rounded(
            this,
            if (selected) Ui.ACCENT else 0xFFF1F3F6.toInt(),
            10f
        )
    }

    /** Android 12 起精确闹钟要单独授权，否则提醒可能晚十几分钟。返回 true 表示弹了授权提示。 */
    private fun askExactAlarm(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !prefs.remindEnabled) return false
        val alarm = getSystemService(AlarmManager::class.java)
        if (alarm.canScheduleExactAlarms()) return false
        AlertDialog.Builder(this)
            .setTitle("开启「闹钟与提醒」权限")
            .setMessage("为了在上课前准点提醒，需要允许本应用设置精确闹钟。")
            .setPositiveButton("去开启") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
            .setNegativeButton("暂不", null)
            .setOnDismissListener { if (!isFinishing) finish() }
            .show()
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

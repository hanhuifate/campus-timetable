package com.whit.timetable

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.whit.timetable.data.AppPrefs
import com.whit.timetable.data.Course
import com.whit.timetable.data.SectionTimes
import com.whit.timetable.data.TimetableStore
import com.whit.timetable.reminder.ReminderNotifier
import com.whit.timetable.reminder.ReminderScheduler
import com.whit.timetable.sync.SyncEngine
import com.whit.timetable.sync.SyncJobService
import com.whit.timetable.ui.CourseEditor
import com.whit.timetable.ui.TimetableGridView
import com.whit.timetable.ui.Ui
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var prefs: AppPrefs
    private lateinit var store: TimetableStore
    private lateinit var grid: TimetableGridView
    private lateinit var weekTitle: TextView
    private lateinit var subtitle: TextView
    private lateinit var todayTitle: TextView
    private lateinit var todayDetail: TextView
    private lateinit var weekBar: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var editButton: TextView

    private var week = 1
    private var courses: List<Course> = emptyList()
    private var editMode = false
    private var clipboard: Course? = null
    private lateinit var rootFrame: FrameLayout
    private var menuCard: View? = null
    private var menuScrim: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        store = TimetableStore(this)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        buildUi()
        SyncJobService.schedule(this)
        week = currentWeek()
        reload()
        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // 从通知进来就当作已读：停掉还在响的提示音并撤掉通知
        ReminderNotifier.stop(this)
        week = currentWeek()
        reload()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Ui.BG)
            setPadding(dp(14f), dp(6f), dp(14f), dp(10f))
        }
        // 外面套一层 FrameLayout，用来放长按弹出的浮动菜单，避免再用独立窗口抢触摸事件。
        rootFrame = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Ui.BG)
        }
        rootFrame.addView(root)

        // 顶部：周次 + 操作
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        weekTitle = Ui.text(this, "第 1 周", 27f, Ui.TEXT_PRIMARY, bold = true)
        subtitle = Ui.text(this, "", 11f, Ui.TEXT_SECONDARY)
        subtitle.setPadding(0, dp(2f), 0, 0)
        titles.addView(weekTitle)
        titles.addView(subtitle)
        header.addView(titles)

        val syncButton = actionButton("同步", Ui.ACCENT, 0xFFE6ECFA.toInt())
        syncButton.setOnClickListener { startSync() }
        editButton = actionButton("编辑", Ui.ACCENT, 0xFFE6ECFA.toInt())
        editButton.setOnClickListener { toggleEdit() }
        val settingsButton = actionButton("设置", Ui.TEXT_SECONDARY, 0xFFECEFF3.toInt())
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        header.addView(syncButton)
        header.addView(editButton)
        header.addView(settingsButton)
        root.addView(header)

        // 今日摘要卡片
        val todayCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@MainActivity, Ui.SURFACE, 14f)
            setPadding(dp(13f), dp(11f), dp(13f), dp(11f))
            elevation = dp(1f).toFloat()
        }
        val cardParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10f) }
        todayCard.layoutParams = cardParams
        todayTitle = Ui.text(this, "", 13f, Ui.TEXT_PRIMARY, bold = true)
        todayDetail = Ui.text(this, "", 11.5f, Ui.TEXT_SECONDARY)
        todayDetail.setPadding(0, dp(3f), 0, 0)
        todayCard.addView(todayTitle)
        todayCard.addView(todayDetail)
        root.addView(todayCard)

        // 课表网格
        val gridWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@MainActivity, Ui.SURFACE, 14f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(10f) }
            setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
        }
        grid = TimetableGridView(this)
        grid.onCourseClick = { showCourse(it) }
        grid.onWeekSwipe = { delta ->
            week = max(1, week + delta)
            renderWeek()
        }
        grid.onEmptyClick = { day, section ->
            if (editMode) openEditor(null, day, section)
        }
        grid.onEmptyLongPress = { day, section ->
            if (editMode) pasteAt(day, section)
        }
        grid.onCourseLongPress = { course, x, y ->
            if (editMode) {
                if (course.custom) {
                    showCourseMenu(course, x, y)
                } else {
                    Toast.makeText(
                        this,
                        "教务导入的课程不能修改，可长按空白处新增自己的课",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        grid.onCourseResize = { course, start, end ->
            resizeCustom(course, start, end)
        }
        gridWrap.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(gridWrap)

        // 底部周切换
        weekBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
        }
        weekBar.addView(navButton("上一周") { week = max(1, week - 1); renderWeek() })
        weekBar.addView(navButton("回到本周") { week = currentWeek(); renderWeek() })
        weekBar.addView(navButton("下一周") { week += 1; renderWeek() })
        root.addView(weekBar)

        statusText = Ui.text(this, "", 10f, Ui.TEXT_SECONDARY, gravity = Gravity.CENTER)
        statusText.setPadding(0, dp(4f), 0, 0)
        root.addView(statusText)

        setContentView(rootFrame)
    }

    private fun actionButton(label: String, textColor: Int, bgColor: Int): TextView {
        return Ui.text(this, label, 12f, textColor, bold = true, gravity = Gravity.CENTER).apply {
            background = Ui.rounded(this@MainActivity, bgColor, 18f)
            setPadding(dp(13f), dp(7f), dp(13f), dp(7f))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(7f)
            layoutParams = lp
        }
    }

    private fun navButton(label: String, onClick: () -> Unit): TextView {
        return Ui.text(this, label, 12f, Ui.ACCENT, gravity = Gravity.CENTER).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(34f), 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun reload() {
        courses = store.loadCourses()
        renderWeek()
        renderStatus()
        // 课表内容或设置变化后立刻重排最近的一条上课提醒
        ReminderScheduler.schedule(this)
    }

    /** Android 13 起发通知需要运行时权限。 */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
    }

    private fun renderWeek() {
        val todayIndex = todayIndex()
        weekTitle.text = "第 $week 周"
        val dateText = SimpleDateFormat("M月d日", Locale.CHINA).format(Date())
        subtitle.text = "${prefs.termName} · $dateText 周${"一二三四五六日"[todayIndex - 1]}"

        grid.dayCount = prefs.dayCount
        grid.week = week
        grid.campus = SectionTimes.campusOf(courses)
        grid.courses = courses

        if (courses.isEmpty()) {
            todayTitle.text = "还没有课表"
            todayDetail.text = "点右上角「同步」，登录教务后自动导入"
        } else {
            val todayCourses = courses
                .filter { it.day == todayIndex && it.weeks.contains(week) }
                .sortedBy { it.startSection }
            if (todayCourses.isEmpty()) {
                todayTitle.text = "今天没有课"
                todayDetail.text = "第 $week 周，共 ${courses.count { it.weeks.contains(week) }} 门课待上"
            } else {
                val campus = SectionTimes.campusOf(courses)
                todayTitle.text = "今天 ${todayCourses.size} 门课"
                todayDetail.text = todayCourses.take(2).joinToString("    ") {
                    val time = SectionTimes.range(campus, it.startSection, it.endSection)
                    val whenText = time.ifEmpty { "第${it.startSection}-${it.endSection}节" }
                    val room = SectionTimes.shortRoom(it.room)
                    "$whenText ${it.name}" + if (room.isEmpty()) "" else " · $room"
                }
            }
        }
    }

    private fun renderStatus() {
        val needLogin = prefs.needsLogin
        statusText.text = when {
            courses.isEmpty() -> "本地模式 · 数据完全存在手机里"
            prefs.lastSync > 0 -> {
                val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(prefs.lastSync))
                "上次同步 $time" + if (needLogin) " · 登录已过期" else " · 共 ${courses.size} 门课"
            }
            else -> "共 ${courses.size} 门课 · 尚未连接教务"
        }
    }

    private fun startSync() {
        if (prefs.username.isEmpty() || prefs.password.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("还没登录")
                .setMessage("需要先填写学号和密码。密码只保存在本机 Keystore 加密存储里，不会上传。")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        if (prefs.cookies.isEmpty()) {
            startActivityForResult(Intent(this, CasLoginActivity::class.java), REQ_LOGIN)
            return
        }
        Toast.makeText(this, "正在同步…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = SyncEngine.refresh(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                if (result.state == SyncEngine.NEED_LOGIN) {
                    // 芜湖教务登录带图形验证码，会话过期只能回 WebView 手动重新登录。
                    prefs.cookies = ""
                    startActivityForResult(Intent(this, CasLoginActivity::class.java), REQ_LOGIN)
                }
                reload()
            }
        }.start()
    }

    private fun showCourse(course: Course) {
        val weeks = course.weeks.sorted()
        val weekText = if (weeks.isEmpty()) "—" else {
            val first = weeks.first()
            val last = weeks.last()
            if (weeks.size == last - first + 1) "$first-$last 周" else weeks.joinToString(",") + " 周"
        }
        val time = SectionTimes.range(
            SectionTimes.campusOf(courses),
            course.startSection,
            course.endSection
        )
        val timeText = "周${"一二三四五六日"[course.day - 1]} 第${course.startSection}-${course.endSection}节" +
            (if (time.isEmpty()) "" else " · $time")
        // 教务给的教师名本身可能带省略号，这里统一成中文顿号加省略号，读起来更自然。
        val teacherText = course.teacher.ifEmpty { "—" }
            .replace(",", "、")
            .replace("..", "…")

        val dialog = Dialog(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@MainActivity, 0xFFF9FAFC.toInt(), 20f)
            setPadding(dp(20f), dp(17f), dp(20f), dp(14f))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(View(this).apply {
            val color = Ui.COURSE_COLORS[
                Math.floorMod(course.colorIndex, Ui.COURSE_COLORS.size)
            ]
            background = Ui.rounded(this@MainActivity, color, 5f)
            layoutParams = LinearLayout.LayoutParams(dp(11f), dp(11f))
        })
        val nameView = Ui.text(this, course.name, 17f, Ui.TEXT_PRIMARY, bold = true)
        nameView.setPadding(dp(9f), 0, 0, 0)
        head.addView(nameView)
        card.addView(head)

        fun row(label: String, value: String) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10f), 0, 0)
            }
            val key = Ui.text(this, label, 11f, Ui.TEXT_SECONDARY)
            key.layoutParams = LinearLayout.LayoutParams(
                dp(42f),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val text = Ui.text(this, value, 12.5f, Ui.TEXT_PRIMARY)
            text.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            line.addView(key)
            line.addView(text)
            card.addView(line)
        }
        row("教师", teacherText)
        row("地点", course.room.ifEmpty { "—" })
        row("时间", timeText)
        row("周次", weekText)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16f), 0, 0)
        }
        bar.addView(
            Ui.text(this, "知道了", 12.5f, Ui.ACCENT, bold = true, gravity = Gravity.CENTER)
                .apply {
                    background = Ui.rounded(this@MainActivity, Color.WHITE, 14f, 1f, Ui.ACCENT)
                    setPadding(dp(20f), dp(9f), dp(20f), dp(9f))
                    setOnClickListener { dialog.dismiss() }
                }
        )
        card.addView(bar)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (this@MainActivity.resources.displayMetrics.widthPixels * 0.86f).toInt()
            setLayout(width, LinearLayout.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply { dimAmount = 0.35f }
        }
        dialog.show()
    }

    private fun toggleEdit() {
        dismissMenu()
        // 还没从教务导入课表时不允许编辑，否则自定义课程会和之后导入的课程重叠。
        if (!editMode && courses.none { !it.custom }) {
            Toast.makeText(this, "请先「同步」导入教务课表，再添加自定义课程", Toast.LENGTH_LONG).show()
            return
        }
        editMode = !editMode
        grid.editMode = editMode
        if (editMode) {
            editButton.text = "完成"
            editButton.setTextColor(0xFFFFFFFF.toInt())
            editButton.background = Ui.rounded(this, Ui.ACCENT, 18f)
            Toast.makeText(
                this,
                "点空白格新增；长按自定义课程可编辑/复制/删除；上下拖动改节次",
                Toast.LENGTH_LONG
            ).show()
        } else {
            editButton.text = "编辑"
            editButton.setTextColor(Ui.ACCENT)
            editButton.background = Ui.rounded(this, 0xFFE6ECFA.toInt(), 18f)
            clipboard = null
        }
    }

    private fun openEditor(initial: Course?, day: Int, section: Int) {
        CourseEditor.show(this, initial, day, section, week) { course ->
            val list = store.loadCourses().toMutableList()
            val index = if (initial == null) {
                -1
            } else {
                list.indexOfFirst { it.custom && isSame(it, initial) }
            }
            val conflict = conflictOf(course, initial)
            if (conflict != null) {
                // 当前这一周看着是空的，但别的周同一节次有课，要把是哪门课、哪几周说清楚。
                Toast.makeText(this, conflictMessage(conflict), Toast.LENGTH_LONG).show()
                false
            } else {
                if (index >= 0) list[index] = course else list.add(course)
                saveList(list)
                Toast.makeText(this, if (index >= 0) "已保存" else "已添加「${course.name}」", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun showCourseMenu(course: Course, touchX: Float, touchY: Float) {
        dismissMenu()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@MainActivity, 0xFFFDFDFE.toInt(), 16f, 1f, 0xFFDDE2EA.toInt())
            elevation = dp(8f).toFloat()
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
        }
        fun addItem(label: String, color: Int, action: () -> Unit) {
            card.addView(
                Ui.text(this, label, 13f, color, bold = true, gravity = Gravity.CENTER).apply {
                    setPadding(dp(15f), dp(11f), dp(15f), dp(11f))
                    setOnClickListener {
                        dismissMenu()
                        action()
                    }
                }
            )
        }
        addItem("编辑", Ui.TEXT_PRIMARY) { openEditor(course, course.day, course.startSection) }
        addItem("复制", Ui.TEXT_PRIMARY) {
            clipboard = course.copy(id = UUID.randomUUID().toString())
            Toast.makeText(this, "已复制，长按空白格粘贴", Toast.LENGTH_SHORT).show()
        }
        addItem("删除", 0xFFD9534F.toInt()) {
            // 自定义课程直接删除，不再弹二次确认。
            saveList(store.loadCourses().filterNot { it.custom && isSame(it, course) })
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
        }

        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val cardWidth = card.measuredWidth
        val cardHeight = card.measuredHeight
        val frameLocation = IntArray(2)
        rootFrame.getLocationInWindow(frameLocation)
        val localX = touchX - frameLocation[0]
        val localY = touchY - frameLocation[1]
        val maxX = rootFrame.width - cardWidth - dp(6f)
        val x = (localX - cardWidth / 2f).toInt().coerceIn(dp(6f), maxX.coerceAtLeast(dp(6f)))
        var y = (localY - cardHeight - dp(12f)).toInt()
        if (y < dp(6f)) y = (localY + dp(20f)).toInt()
            .coerceAtMost((rootFrame.height - cardHeight - dp(6f)).coerceAtLeast(dp(6f)))

        // 半透明遮罩负责“点空白关闭”，菜单本身不动，避免独立窗口抢走下一次触摸。
        val scrim = View(this).apply {
            setBackgroundColor(0x11000000)
            setOnClickListener { dismissMenu() }
        }
        rootFrame.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val params = FrameLayout.LayoutParams(cardWidth, cardHeight).apply {
            leftMargin = x
            topMargin = y
        }
        rootFrame.addView(card, params)
        menuCard = card
        menuScrim = scrim
    }

    private fun dismissMenu() {
        menuCard?.let { rootFrame.removeView(it) }
        menuScrim?.let { rootFrame.removeView(it) }
        menuCard = null
        menuScrim = null
        grid.resetTouchState()
    }

    /** 复制后长按空白格：保持原时长，落到手指所在的星期和起始节。 */
    private fun pasteAt(day: Int, section: Int) {
        val clip = clipboard
        if (clip == null) {
            Toast.makeText(this, "先长按自定义课程选择「复制」", Toast.LENGTH_SHORT).show()
            return
        }
        val duration = (clip.endSection - clip.startSection).coerceAtLeast(0)
        val start = section.coerceIn(1, 12)
        val end = (start + duration).coerceAtMost(12)
        val conflict = conflictOf(clip.copy(day = day, startSection = start, endSection = end))
        if (conflict != null) {
            Toast.makeText(this, conflictMessage(conflict), Toast.LENGTH_LONG).show()
            return
        }
        val pasted = clip.copy(
            id = UUID.randomUUID().toString(),
            day = day,
            startSection = start,
            endSection = end,
            custom = true
        )
        saveList(store.loadCourses() + pasted)
        Toast.makeText(
            this,
            "已粘贴到周${"一二三四五六日"[day - 1]} 第$start-$end 节",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun resizeCustom(course: Course, start: Int, end: Int) {
        // 双保险：万一拖拽夹不住，这里也不允许与同天同周次的课重叠。
        val conflict = conflictOf(
            course.copy(startSection = start, endSection = end),
            course
        )
        if (conflict != null) {
            Toast.makeText(this, conflictMessage(conflict), Toast.LENGTH_LONG).show()
            renderWeek()
            return
        }
        val list = store.loadCourses().map {
            if (it.custom && isSame(it, course)) {
                it.copy(startSection = start, endSection = end)
            } else {
                it
            }
        }
        saveList(list)
    }

    /** 找出与 candidate 同天、周次有交集、节次重叠的已有课程（可排除自身）。 */
    private fun conflictOf(candidate: Course, exclude: Course? = null): Course? {
        return courses.firstOrNull { other ->
            other !== exclude &&
                !(exclude != null && other.custom && isSame(other, exclude)) &&
                other.day == candidate.day &&
                other.weeks.any { candidate.weeks.contains(it) } &&
                other.startSection <= candidate.endSection &&
                other.endSection >= candidate.startSection
        }
    }

    private fun conflictMessage(other: Course): String {
        val weeks = Course.weeksToText(other.weeks)
        return "与「${other.name}」（第${weeks}周 周${"一二三四五六日"[other.day - 1]} " +
            "第${other.startSection}-${other.endSection}节）时间冲突"
    }

    private fun saveList(list: List<Course>) {
        store.saveCourses(list)
        courses = list
        renderWeek()
        renderStatus()
    }

    private fun isSame(a: Course, b: Course): Boolean =
        (a.id.isNotEmpty() && a.id == b.id) || a.identity() == b.identity()

    private fun currentWeek(): Int {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val start = try {
            format.parse(prefs.termStart)?.time
        } catch (e: Exception) {
            null
        } ?: return 1
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val days = (cal.timeInMillis - start) / 86400000L
        return if (days < 0) 1 else (days / 7).toInt() + 1
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 登录页已直接把课表写进本地，这里只需要刷新界面
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK) reload()
    }

    private fun todayIndex(): Int {
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    override fun onPause() {
        super.onPause()
        dismissMenu()
    }

    override fun onBackPressed() {
        if (menuCard != null) {
            dismissMenu()
            return
        }
        super.onBackPressed()
    }

    private fun dp(value: Float): Int = Ui.px(this, value)

    private companion object {
        const val REQ_LOGIN = 11
        const val REQ_NOTIFY = 12
    }
}

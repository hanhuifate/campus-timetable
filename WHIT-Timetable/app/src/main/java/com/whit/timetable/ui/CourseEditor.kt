package com.whit.timetable.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.whit.timetable.data.Course
import java.util.UUID

/**
 * 自定义课程编辑器：新增和编辑共用一套表单。
 * 只处理用户自建课程，教务导入的课程不会走到这里。
 * 界面做成居中的浅色圆角卡片，不再用铺满屏幕的白色弹窗。
 */
object CourseEditor {

    fun show(
        activity: Activity,
        initial: Course?,
        day: Int,
        section: Int,
        defaultWeek: Int,
        onSave: (Course) -> Boolean
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(activity, 0xFFF8FAFC.toInt(), 20f)
            setPadding(dp(20f), dp(16f), dp(20f), dp(12f))
        }

        card.addView(
            Ui.text(
                activity,
                if (initial == null) "新增课程" else "编辑课程",
                16f,
                Ui.TEXT_PRIMARY,
                bold = true
            )
        )
        val tip = Ui.text(activity, "自定义课程只存本机，同步教务时不会被覆盖", 10.5f, Ui.TEXT_SECONDARY)
        tip.setPadding(0, dp(4f), 0, 0)
        card.addView(tip)

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val nameField = field(activity, "如 高等数学A(1)", initial?.name ?: "")
        val teacherField = field(activity, "选填", initial?.teacher ?: "")
        val roomField = field(activity, "如 6号楼 6202", initial?.room ?: "")

        box.addView(label(activity, "课程名"))
        box.addView(nameField)
        box.addView(label(activity, "教师"))
        box.addView(teacherField)
        box.addView(label(activity, "地点"))
        box.addView(roomField)

        // 星期：七个可选按钮，比让用户手输数字直观。
        var selectedDay = if (initial != null) initial.day else day.coerceIn(1, 7)
        box.addView(label(activity, "星期"))
        val dayRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dayChips = ArrayList<TextView>()
        for (i in 1..7) {
            val chip = Ui.text(
                activity,
                "周${"一二三四五六日"[i - 1]}",
                11f,
                Ui.TEXT_SECONDARY,
                gravity = Gravity.CENTER
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(34f), 1f).apply {
                    marginEnd = dp(4f)
                }
                setOnClickListener {
                    selectedDay = i
                    dayChips.forEachIndexed { index, view -> styleChip(activity, view, index + 1 == selectedDay) }
                }
            }
            dayChips.add(chip)
            dayRow.addView(chip)
        }
        dayChips.forEachIndexed { index, view -> styleChip(activity, view, index + 1 == selectedDay) }
        box.addView(dayRow)

        val sectionDefault = if (initial != null) {
            "${initial.startSection}-${initial.endSection}"
        } else {
            "$section-$section"
        }
        val sectionField = field(activity, "如 5-6", sectionDefault)
        sectionField.inputType = InputType.TYPE_CLASS_TEXT
        val weekDefault = if (initial != null && initial.weeks.isNotEmpty()) {
            Course.weeksToText(initial.weeks)
        } else {
            // 新增时默认只上当前显示的这一周，避免默认 1-16 周与其它周的课冲突。
            "$defaultWeek"
        }
        val weekField = field(activity, "如 1-16", weekDefault)

        box.addView(label(activity, "节次（起-止，1-12）"))
        box.addView(sectionField)
        box.addView(label(activity, "周次（如 1-16,18）"))
        box.addView(weekField)

        val scroll = ScrollView(activity).apply {
            addView(box)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(scroll)

        val dialog = Dialog(activity)
        var saveListener: () -> Unit = {}

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, 0)
        }
        val cancel = Ui.text(activity, "取消", 13f, Ui.TEXT_SECONDARY, bold = true, gravity = Gravity.CENTER)
            .apply {
                background = Ui.rounded(activity, 0xFFF6F8FA.toInt(), 14f, 1f, Ui.LINE)
                layoutParams = LinearLayout.LayoutParams(0, dp(42f), 1f).apply { marginEnd = dp(8f) }
                setOnClickListener { dialog.dismiss() }
            }
        val save = Ui.text(activity, "保存", 13f, Color.WHITE, bold = true, gravity = Gravity.CENTER)
            .apply {
                background = Ui.rounded(activity, Ui.ACCENT, 14f)
                layoutParams = LinearLayout.LayoutParams(0, dp(42f), 1f)
                setOnClickListener { saveListener() }
            }
        bar.addView(cancel)
        bar.addView(save)
        card.addView(bar)

        saveListener = saveLabel@{
            val name = nameField.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(activity, "请填写课程名", Toast.LENGTH_SHORT).show()
                return@saveLabel
            }
            val range = Regex("(\\d{1,2})\\s*[-~～]\\s*(\\d{1,2})").find(
                sectionField.text.toString()
            )
            val single = Regex("^\\s*(\\d{1,2})\\s*$").find(sectionField.text.toString())
            var start = range?.groupValues?.get(1)?.toIntOrNull()
                ?: single?.groupValues?.get(1)?.toIntOrNull()
            var end = range?.groupValues?.get(2)?.toIntOrNull() ?: start
            if (start == null) {
                Toast.makeText(activity, "节次格式应为 5-6", Toast.LENGTH_SHORT).show()
                return@saveLabel
            }
            start = start.coerceIn(1, 12)
            end = (end ?: start).coerceIn(start, 12)

            val weeks = Course.textToWeeks(weekField.text.toString())
            if (weeks.isEmpty()) {
                Toast.makeText(activity, "周次格式应为 1-16", Toast.LENGTH_SHORT).show()
                return@saveLabel
            }

            val course = Course(
                name = name,
                teacher = teacherField.text.toString().trim(),
                room = roomField.text.toString().trim(),
                day = selectedDay,
                startSection = start,
                endSection = end,
                weeks = weeks,
                colorIndex = initial?.colorIndex
                    ?: Math.floorMod(name.hashCode(), Ui.COURSE_COLORS.size),
                id = initial?.id?.ifEmpty { UUID.randomUUID().toString() }
                    ?: UUID.randomUUID().toString(),
                custom = true
            )
            if (onSave(course)) dialog.dismiss()
        }

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
            setLayout(width, LinearLayout.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply { dimAmount = 0.35f }
        }
        dialog.show()
    }

    private fun label(activity: Activity, text: String): TextView =
        Ui.text(activity, text, 11f, Ui.TEXT_SECONDARY).apply {
            setPadding(0, Ui.px(activity, 8f), 0, Ui.px(activity, 3f))
        }

    private fun field(activity: Activity, hint: String, value: String): EditText =
        EditText(activity).apply {
            this.hint = hint
            setText(value)
            textSize = 14f
            setTextColor(Ui.TEXT_PRIMARY)
            setHintTextColor(0xFF9AA1AC.toInt())
            background = Ui.rounded(activity, Color.WHITE, 10f, 1f, Ui.LINE)
            setPadding(
                Ui.px(activity, 11f),
                Ui.px(activity, 9f),
                Ui.px(activity, 11f),
                Ui.px(activity, 9f)
            )
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            inputType = InputType.TYPE_CLASS_TEXT
        }

    private fun styleChip(activity: Activity, chip: TextView, selected: Boolean) {
        chip.setTextColor(if (selected) Color.WHITE else Ui.TEXT_SECONDARY)
        chip.background = Ui.rounded(
            activity,
            if (selected) Ui.ACCENT else 0xFFEFF2F6.toInt(),
            9f
        )
    }
}

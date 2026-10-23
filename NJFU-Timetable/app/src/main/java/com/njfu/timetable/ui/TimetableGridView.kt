package com.njfu.timetable.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.njfu.timetable.data.Course
import com.njfu.timetable.data.SectionTimes
import java.util.Calendar
import kotlin.math.abs

/**
 * 自绘课表网格：没有列表、没有广告位，整块一次性绘制，
 * 冷启动时只依赖本地数据，首帧即为完整课表。
 */
class TimetableGridView(context: Context) : View(context) {

    var courses: List<Course> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var week: Int = 1
        set(value) {
            field = value
            invalidate()
        }

    var dayCount: Int = 7
        set(value) {
            field = value.coerceIn(5, 7)
            invalidate()
        }

    /** 当前作息表所属校区，由设置页决定。 */
    var campus: String = "白马"
        set(value) {
            field = value
            invalidate()
        }

    /** 编辑模式：空白处可新增，自定义课程可长按操作、拖拽改节次。 */
    var editMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                dragging = false
                dragCourse = null
            }
            invalidate()
        }

    var onCourseClick: ((Course) -> Unit)? = null
    var onWeekSwipe: ((Int) -> Unit)? = null
    var onEmptyClick: ((Int, Int) -> Unit)? = null
    var onEmptyLongPress: ((Int, Int) -> Unit)? = null
    var onCourseLongPress: ((Course, Float, Float) -> Unit)? = null
    var onCourseResize: ((Course, Int, Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val sectionCount = 12
    private val leftWidth = 40f * density
    private val headerHeight = 32f * density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFFE7EAEF.toInt()
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * scaledDensity
        color = Ui.TEXT_SECONDARY
        textAlign = Paint.Align.CENTER
    }
    private val headActivePaint = Paint(headPaint).apply {
        color = Ui.ACCENT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * scaledDensity
        color = 0xFF9AA1AC.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val sectionTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 6.6f * scaledDensity
        textScaleX = 0.92f
        color = 0xFFB4BAC4.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val courseNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 9.5f * scaledDensity
        textScaleX = 0.96f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val courseRoomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textSize = 7.6f * scaledDensity
        textScaleX = 0.9f
        textAlign = Paint.Align.CENTER
    }
    private val courseTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xC0FFFFFF.toInt()
        textSize = 6.8f * scaledDensity
        textScaleX = 0.92f
        textAlign = Paint.Align.CENTER
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x332E63D8
        style = Paint.Style.FILL
    }
    private val addHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * scaledDensity
        color = 0xFFC9CFD8.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x802E63D8.toInt()
        style = Paint.Style.FILL
    }

    private var downX = 0f
    private var downY = 0f
    private var downScreenX = 0f
    private var downScreenY = 0f
    private var moved = false

    /** 手指移动超过这个距离就算滑动，不再触发长按和单击。 */
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 1.4f

    private var longPressFired = false
    private var touchingCourse: Course? = null
    private var touchDay = 0
    private var touchSection = 0

    private var dragging = false
    private var dragCourse: Course? = null
    private var dragStart = 0
    private var dragEnd = 0
    private var dragAdjustStart = false

    private val longPressRunnable = Runnable {
        if (dragging || moved) return@Runnable
        // 长按只做震动反馈，菜单等手指抬起后再弹，避免浮层被当前的抬手事件点掉。
        longPressFired = true
        if (!editMode) return@Runnable
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun colWidth(): Float = (width - leftWidth) / dayCount
    private fun rowHeight(): Float = (height - headerHeight) / sectionCount

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val colW = colWidth()
        val rowH = rowHeight()
        if (colW <= 0 || rowH <= 0) return

        val today = todayColumn()

        // 表头
        for (i in 1..dayCount) {
            val label = "周${"一二三四五六日"[i - 1]}"
            val cx = leftWidth + (i - 1) * colW + colW / 2
            val paint = if (i == today) headActivePaint else headPaint
            val baseline = headerHeight / 2 + headPaint.textSize / 3
            canvas.drawText(label, cx, baseline, paint)
            if (i == today) {
                canvas.drawCircle(cx, headerHeight - 5f * density, 2f * density, Paint().apply { color = Ui.ACCENT })
            }
        }

        // 网格
        for (i in 0..sectionCount) {
            val y = headerHeight + i * rowH
            canvas.drawLine(leftWidth, y, width.toFloat(), y, linePaint)
        }
        for (i in 0..dayCount) {
            val x = leftWidth + i * colW
            canvas.drawLine(x, headerHeight, x, height.toFloat(), linePaint)
        }

        // 节次：上方节号，下方开始时间
        for (i in 1..sectionCount) {
            val cy = headerHeight + (i - 0.5f) * rowH
            canvas.drawText(
                i.toString(),
                leftWidth / 2,
                cy - 1.5f * density + sectionPaint.textSize / 3,
                sectionPaint
            )
            val start = SectionTimes.start(campus, i)
            if (start.isNotEmpty()) {
                canvas.drawText(
                    start,
                    leftWidth / 2,
                    cy + 8f * density + sectionTimePaint.textSize / 3,
                    sectionTimePaint
                )
            }
        }

        // 今日列淡色底
        if (today in 1..dayCount) {
            val x = leftWidth + (today - 1) * colW
            canvas.drawRect(x, headerHeight, x + colW, height.toFloat(), nowPaint)
        }

        // 编辑模式：空白格画「+」，提示可点此新增
        if (editMode) {
            val dy = addHintPaint.textSize / 3
            for (d in 1..dayCount) {
                for (s in 1..sectionCount) {
                    val occupied = courses.any {
                        it.day == d && it.weeks.contains(week) && s in it.startSection..it.endSection
                    }
                    if (!occupied) {
                        val cx = leftWidth + (d - 0.5f) * colW
                        val cy = headerHeight + (s - 0.5f) * rowH
                        canvas.drawText("+", cx, cy + dy, addHintPaint)
                    }
                }
            }
        }

        // 拖拽调整节次时的预览块
        if (dragging && dragCourse != null) {
            val dc = dragCourse!!
            val x = leftWidth + (dc.day - 1) * colW
            val y = headerHeight + (dragStart - 1) * rowH
            canvas.drawRoundRect(
                x + 1.5f * density,
                y + 1.5f * density,
                x + colW - 1.5f * density,
                headerHeight + dragEnd * rowH - 1.5f * density,
                5f * density,
                5f * density,
                previewPaint
            )
        }

        // 课程块
        val rect = RectF()
        courses.filter { it.weeks.contains(week) }.forEach { course ->
            if (course.day > dayCount) return@forEach
            val start = course.startSection.coerceIn(1, sectionCount)
            val end = course.endSection.coerceIn(start, sectionCount)
            val x = leftWidth + (course.day - 1) * colW
            val y = headerHeight + (start - 1) * rowH
            rect.set(
                x + 1.5f * density,
                y + 1.5f * density,
                x + colW - 1.5f * density,
                headerHeight + end * rowH - 1.5f * density
            )
            blockPaint.color = Ui.COURSE_COLORS[course.colorIndex % Ui.COURSE_COLORS.size]
            canvas.drawRoundRect(rect, 5f * density, 5f * density, blockPaint)

            val innerWidth = rect.width() - 4f * density
            val padTop = 3f * density
            val nameLH = courseNamePaint.textSize + 1.5f * density
            val roomLH = courseRoomPaint.textSize + 1.5f * density
            val timeLH = courseTimePaint.textSize + 1.2f * density
            val usableHeight = rect.height() - padTop * 2

            // 教室去掉重复的校区前缀，尽量在格子里完整显示；时间也一并放进去。
            val roomText = SectionTimes.shortRoom(course.room)
            val timeText = SectionTimes.range(campus, start, end)
            var roomLines = if (roomText.isEmpty()) emptyList()
            else wrap(roomText, courseRoomPaint, innerWidth, 2)
            var timeLines = if (timeText.isEmpty()) emptyList()
            else listOf(fit(timeText, courseTimePaint, innerWidth))
            var nameLines = wrap(course.name, courseNamePaint, innerWidth, 3)

            // 空间不够时按“名称 > 教室 > 时间”的顺序取舍。
            while (nameLines.size * nameLH + roomLines.size * roomLH + timeLines.size * timeLH > usableHeight) {
                when {
                    timeLines.isNotEmpty() -> timeLines = emptyList()
                    roomLines.size > 1 -> roomLines = roomLines.take(1)
                    roomLines.isNotEmpty() -> roomLines = emptyList()
                    nameLines.size > 1 -> nameLines = nameLines.dropLast(1)
                    else -> break
                }
            }

            val contentHeight =
                nameLines.size * nameLH + roomLines.size * roomLH + timeLines.size * timeLH
            var textTop = rect.top + padTop + ((usableHeight - contentHeight) / 2).coerceAtLeast(0f)
            nameLines.forEach { line ->
                canvas.drawText(line, rect.centerX(), textTop + courseNamePaint.textSize, courseNamePaint)
                textTop += nameLH
            }
            roomLines.forEach { line ->
                canvas.drawText(line, rect.centerX(), textTop + courseRoomPaint.textSize, courseRoomPaint)
                textTop += roomLH
            }
            timeLines.forEach { line ->
                canvas.drawText(line, rect.centerX(), textTop + courseTimePaint.textSize, courseTimePaint)
                textTop += timeLH
            }
        }
    }

    private fun fit(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        if (text.isEmpty() || maxWidth <= 0) return emptyList()
        val lines = ArrayList<String>()
        var current = StringBuilder()
        for (ch in text) {
            val candidate = current.toString() + ch
            if (paint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                lines.add(current.toString())
                current = StringBuilder().append(ch)
                if (lines.size == maxLines) break
            } else {
                current.append(ch)
            }
        }
        if (lines.size < maxLines && current.isNotEmpty()) {
            lines.add(current.toString())
        }
        return lines
    }

    private fun todayColumn(): Int {
        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        val colW = colWidth()
        val rowH = rowHeight()
        if (colW <= 0 || rowH <= 0) return null
        if (x < leftWidth || y < headerHeight || x > width || y > height) return null
        val day = ((x - leftWidth) / colW).toInt() + 1
        val section = ((y - headerHeight) / rowH).toInt() + 1
        if (day !in 1..dayCount || section !in 1..sectionCount) return null
        return day to section
    }

    private fun sectionAt(y: Float): Int {
        val rowH = rowHeight()
        if (rowH <= 0) return 1
        return (((y - headerHeight) / rowH).toInt() + 1).coerceIn(1, sectionCount)
    }

    private fun courseAt(x: Float, y: Float): Course? {
        val cell = cellAt(x, y) ?: return null
        return courses.firstOrNull {
            it.day == cell.first && cell.second in it.startSection..it.endSection && it.weeks.contains(week)
        }
    }

    /**
     * 拖拽时可用的节次范围：遇到同一天、周次有交集的其它课程就停下，
     * 避免把课程拖到别人身上造成重叠。
     */
    private fun resizeBounds(course: Course): Pair<Int, Int> {
        var minStart = 1
        var maxEnd = sectionCount
        courses.forEach { other ->
            if (other === course) return@forEach
            if (other.day != course.day) return@forEach
            if (other.weeks.none { course.weeks.contains(it) }) return@forEach
            val os = other.startSection.coerceIn(1, sectionCount)
            val oe = other.endSection.coerceIn(os, sectionCount)
            if (oe < course.startSection) minStart = maxOf(minStart, oe + 1)
            if (os > course.endSection) maxEnd = minOf(maxEnd, os - 1)
        }
        return minStart to maxEnd
    }

    /** 浮层关闭或界面切走时清掉手势状态，保证下一次长按还能正常触发。 */
    fun resetTouchState() {
        removeCallbacks(longPressRunnable)
        longPressFired = false
        moved = false
        dragging = false
        dragCourse = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                longPressFired = false
                moved = false
                dragging = false
                dragCourse = null
                val loc = IntArray(2)
                getLocationInWindow(loc)
                downScreenX = loc[0] + event.x
                downScreenY = loc[1] + event.y
                val cell = cellAt(event.x, event.y)
                touchDay = cell?.first ?: 0
                touchSection = cell?.second ?: 0
                touchingCourse = courseAt(event.x, event.y)
                if (editMode) postDelayed(longPressRunnable, 450)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val course = touchingCourse
                val section = sectionAt(event.y)
                // 一旦滑动就取消长按，避免滑动过程中弹出菜单。
                if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    moved = true
                    removeCallbacks(longPressRunnable)
                }
                // 自定义课程：上下拖拽改变起止节次（向上/向下都能伸缩）。
                if (editMode && course != null && course.custom && !longPressFired && moved &&
                    abs(dy) > abs(dx)
                ) {
                    if (!dragging) {
                        dragging = true
                        dragCourse = course
                        dragStart = course.startSection
                        dragEnd = course.endSection
                        dragAdjustStart = touchSection <= (course.startSection + course.endSection) / 2
                    }
                    when {
                        section < dragStart -> dragStart = section
                        section > dragEnd -> dragEnd = section
                        dragAdjustStart -> dragStart = section.coerceIn(1, dragEnd)
                        else -> dragEnd = section.coerceIn(dragStart, sectionCount)
                    }
                    // 夹在相邻课程之间，拖不过去也就不会叠在一起。
                    val (minStart, maxEnd) = resizeBounds(course)
                    dragStart = dragStart.coerceIn(minStart, dragEnd)
                    dragEnd = dragEnd.coerceIn(dragStart, maxEnd)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val dx = event.x - downX
                val dy = event.y - downY
                if (dragging) {
                    dragging = false
                    val course = dragCourse
                    dragCourse = null
                    if (course != null &&
                        (dragStart != course.startSection || dragEnd != course.endSection)
                    ) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        onCourseResize?.invoke(course, dragStart, dragEnd)
                    }
                    invalidate()
                    return true
                }
                if (longPressFired) {
                    // 长按已经在抬手时弹菜单，不再当作点击。
                    val course = touchingCourse
                    if (course != null) {
                        onCourseLongPress?.invoke(course, downScreenX, downScreenY)
                    } else if (touchDay > 0) {
                        onEmptyLongPress?.invoke(touchDay, touchSection)
                    }
                    return true
                }
                if (!editMode && abs(dx) > 60f * density && abs(dx) > abs(dy) * 1.5f) {
                    onWeekSwipe?.invoke(if (dx < 0) 1 else -1)
                    return true
                }
                if (!moved && touchDay > 0) {
                    val course = touchingCourse
                    if (course != null) {
                        onCourseClick?.invoke(course)
                    } else if (editMode) {
                        onEmptyClick?.invoke(touchDay, touchSection)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                dragging = false
                dragCourse = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

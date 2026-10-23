package com.njfu.timetable.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView

object Ui {

    val COURSE_COLORS = intArrayOf(
        0xFF4A7BD8.toInt(),
        0xFF2E9E7E.toInt(),
        0xFFD07A35.toInt(),
        0xFF8B5CBF.toInt(),
        0xFF3C93AE.toInt(),
        0xFFC25B78.toInt(),
        0xFF6C8A3C.toInt(),
        0xFF56657F.toInt()
    )

    val BG = 0xFFF2F4F7.toInt()
    val SURFACE = Color.WHITE
    val TEXT_PRIMARY = 0xFF171A1F.toInt()
    val TEXT_SECONDARY = 0xFF6B7280.toInt()
    val ACCENT = 0xFF2E63D8.toInt()
    val LINE = 0xFFE4E7EC.toInt()

    fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    fun px(context: Context, value: Float): Int = dp(context, value).toInt()

    fun rounded(context: Context, color: Int, radiusDp: Float, strokeDp: Float = 0f, strokeColor: Int = 0): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.setColor(color)
        drawable.cornerRadius = dp(context, radiusDp)
        if (strokeDp > 0f) {
            drawable.setStroke(dp(context, strokeDp).toInt(), strokeColor)
        }
        return drawable
    }

    fun text(
        context: Context,
        content: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL
    ): TextView {
        return TextView(context).apply {
            text = content
            textSize = sizeSp
            setTextColor(color)
            this.gravity = gravity
            includeFontPadding = false
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
}

package com.floattime.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Calendar

/**
 * 一周日期选择条
 * 显示本周7天，当前日期用橙色圆点标记
 */
class WeekDateView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dayLabels = arrayOf("日", "一", "二", "三", "四", "五", "六")
    private val dates = mutableListOf<DateItem>()

    data class DateItem(
        val dayNum: Int,
        val dayLabel: String,
        val isToday: Boolean,
        val calendar: Calendar
    )

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 28f
        color = 0xFF333333.toInt()
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 22f
        color = 0xFF999999.toInt()
    }

    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 28f
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.DEFAULT_BOLD
    }

    private val selectedLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 22f
        color = 0xFFFFFFFF.toInt()
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFF6B35.toInt()
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFF6B35.toInt()
    }

    init {
        buildWeekDates()
    }

    private fun buildWeekDates() {
        dates.clear()
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)

        val today = Calendar.getInstance()
        val todayDate = today.get(Calendar.DAY_OF_YEAR)
        val todayYear = today.get(Calendar.YEAR)

        for (i in 0..6) {
            val c = cal.clone() as Calendar
            val isToday = c.get(Calendar.DAY_OF_YEAR) == todayDate &&
                    c.get(Calendar.YEAR) == todayYear
            dates.add(DateItem(
                dayNum = c.get(Calendar.DAY_OF_MONTH),
                dayLabel = dayLabels[i],
                isToday = isToday,
                calendar = c
            ))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dpToPx(72f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cellWidth = w / 7f
        val radius = dpToPx(20f)

        for (i in dates.indices) {
            val item = dates[i]
            val cx = cellWidth * i + cellWidth / 2f

            if (item.isToday) {
                canvas.drawCircle(cx, h * 0.35f, radius, bgPaint)
                canvas.drawText(item.dayNum.toString(), cx, h * 0.35f + dpToPx(8f), selectedPaint)
                canvas.drawText(item.dayLabel, cx, h * 0.78f, selectedLabelPaint)
                canvas.drawCircle(cx, h * 0.92f, dpToPx(3f), dotPaint)
            } else {
                canvas.drawText(item.dayNum.toString(), cx, h * 0.35f + dpToPx(8f), textPaint)
                canvas.drawText(item.dayLabel, cx, h * 0.78f, labelPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return true
    }

    private fun dpToPx(dp: Float): Float =
        dp * resources.displayMetrics.density
}

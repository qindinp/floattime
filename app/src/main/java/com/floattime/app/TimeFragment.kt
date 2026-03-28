package com.floattime.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 状态页 Fragment
 * 显示大圆环进度、今日/本月/年占比
 */
class TimeFragment : Fragment() {

    private lateinit var dateSubtitle: TextView
    private lateinit var circleProgress: CircularProgressView
    private lateinit var percentLabel: TextView
    private lateinit var runTimeText: TextView
    private lateinit var cardTodayPercent: TextView
    private lateinit var cardMonthPercent: TextView
    private lateinit var cardYearPercent: TextView
    private lateinit var cardTodayBar: View
    private lateinit var cardMonthBar: View
    private lateinit var cardYearBar: View

    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    private val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // Simulated "service start time" for running time display
    private val serviceStartTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(5)
        .minus(TimeUnit.HOURS.toMillis(17))

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_time, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dateSubtitle = view.findViewById(R.id.dateSubtitle)
        circleProgress = view.findViewById(R.id.circleProgress)
        percentLabel = view.findViewById(R.id.percentLabel)
        runTimeText = view.findViewById(R.id.runTimeText)
        cardTodayPercent = view.findViewById(R.id.cardTodayPercent)
        cardMonthPercent = view.findViewById(R.id.cardMonthPercent)
        cardYearPercent = view.findViewById(R.id.cardYearPercent)
        cardTodayBar = view.findViewById(R.id.cardTodayBar)
        cardMonthBar = view.findViewById(R.id.cardMonthBar)
        cardYearBar = view.findViewById(R.id.cardYearBar)

        startUpdateLoop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        updateRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun startUpdateLoop() {
        updateRunnable = object : Runnable {
            override fun run() {
                updateTimeDisplay()
                handler.postDelayed(this, 1000)
            }
        }.also { handler.post(it) }
    }

    private fun updateTimeDisplay() {
        if (!isAdded) return

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        // Date subtitle: "2026/02/08 普通的一天"
        val dateStr = dateFormat.format(Date(now))
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val dayType = when (dayOfWeek) {
            Calendar.SATURDAY, Calendar.SUNDAY -> "周末"
            else -> "普通的一天"
        }
        dateSubtitle.text = "$dateStr $dayType"

        // Today percentage
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        val millisInDay = (hour * 3600 + minute * 60 + second) * 1000L
        val totalDayMs = 24 * 3600 * 1000L
        val todayPercent = millisInDay.toDouble() / totalDayMs * 100

        // Center time display
        val timeStr = timeFormat.format(Date(now))
        circleProgress.setCenterTime(timeStr)
        circleProgress.setProgress(todayPercent.toFloat())

        percentLabel.text = "今天已过去 ${"%.1f".format(todayPercent)}%"

        // Running time
        val elapsed = now - serviceStartTime
        val days = TimeUnit.MILLISECONDS.toDays(elapsed)
        val hours = TimeUnit.MILLISECONDS.toHours(elapsed) % 24
        runTimeText.text = "当前运行时间: ${days}d ${hours}h"

        // Month percentage
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val monthPercent = dayOfMonth.toDouble() / daysInMonth * 100

        // Year percentage
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val daysInYear = if (cal.getActualMaximum(Calendar.DAY_OF_YEAR) > 365) 366 else 365
        val yearPercent = dayOfYear.toDouble() / daysInYear * 100

        // Update cards
        cardTodayPercent.text = "${"%.1f".format(todayPercent)}%"
        cardMonthPercent.text = "${"%.1f".format(monthPercent)}%"
        cardYearPercent.text = "${"%.1f".format(yearPercent)}%"

        // Update progress bars
        updateBar(cardTodayBar, todayPercent.toFloat())
        updateBar(cardMonthBar, monthPercent.toFloat())
        updateBar(cardYearBar, yearPercent.toFloat())
    }

    private fun updateBar(bar: View, percent: Float) {
        bar.post {
            val container = bar.parent as? View ?: return@post
            val maxWidth = container.width
            if (maxWidth > 0) {
                val lp = bar.layoutParams
                lp.width = (maxWidth * percent / 100f).toInt()
                bar.layoutParams = lp
            }
        }
    }
}

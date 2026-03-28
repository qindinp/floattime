package com.floattime.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 状态 Tab 内容 (底部抽屉)
 * 显示校准时间、今日已过占比等信息
 */
class TimeSheetFragment : Fragment() {

    private lateinit var currentTimeText: TextView
    private lateinit var currentOffsetText: TextView
    private lateinit var dayDetailText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(android.R.layout.simple_list_item_3, container, false)
        // Use a custom inline layout instead
        return createView()
    }

    private fun createView(): View {
        val ctx = requireContext()
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        currentTimeText = TextView(ctx).apply {
            textSize = 20f
            setTextColor(0xFFFF6B35.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "--:--:--"
        }

        currentOffsetText = TextView(ctx).apply {
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            text = "校准时间 (淘宝) | 偏移量: 0ms"
            val lp = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4 }
            layoutParams = lp
        }

        dayDetailText = TextView(ctx).apply {
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            text = ""
            val lp = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            layoutParams = lp
        }

        root.addView(currentTimeText)
        root.addView(currentOffsetText)
        root.addView(dayDetailText)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        startUpdateLoop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        updateRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun startUpdateLoop() {
        updateRunnable = object : Runnable {
            override fun run() {
                updateDisplay()
                handler.postDelayed(this, 1000)
            }
        }.also { handler.post(it) }
    }

    private fun updateDisplay() {
        if (!isAdded) return

        val prefs = requireContext().getSharedPreferences("FloatTimePrefs", android.content.Context.MODE_PRIVATE)
        val offsetMs = prefs.getLong("offset_ms", 0)
        val timeSource = prefs.getString("time_source", "taobao") ?: "taobao"

        val now = System.currentTimeMillis() + offsetMs
        val millis = now % 1000
        val seconds = (now / 1000) % 60
        val minutes = (now / 60000) % 60
        val hours = (now / 3600000) % 24

        currentTimeText.text = String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)

        val sourceName = when (timeSource) {
            "taobao" -> "淘宝"
            "meituan" -> "美团"
            else -> "本地"
        }
        currentOffsetText.text = "校准时间 ($sourceName) | 偏移量: ${offsetMs}ms"

        // Day details
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        val millisInDay = (hour * 3600 + minute * 60 + second) * 1000L
        val totalDayMs = 24 * 3600 * 1000L
        val passedMinutes = (millisInDay / 60000).toInt()
        val passedHours = passedMinutes / 60
        val passedMins = passedMinutes % 60
        val remainMinutes = (totalDayMs - millisInDay) / 60000
        val remainHours = remainMinutes.toInt() / 60
        val remainMins = remainMinutes.toInt() % 60

        dayDetailText.text = "${passedHours}小时${passedMins}分钟已过去, 还剩下${remainHours}小时${remainMins}分钟"
    }
}

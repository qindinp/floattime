package com.floattime.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BubbleActivity - 用于显示展开的 Live Activity
 * 
 * 当用户点击 Bubble 时，会展开显示这个 Activity
 * 显示实时刷新的时间信息
 */
class BubbleActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BubbleActivity"
        
        // 用于 Intent Extra 的常量
        const val EXTRA_TIME_SOURCE = "time_source"
        const val EXTRA_TIME = "time"
        const val EXTRA_MILLIS = "millis"
    }

    private lateinit var mTimeText: TextView
    private lateinit var mMillisText: TextView
    private lateinit var mSourceText: TextView
    
    private var mTimeSource: String = "悬浮时间"
    private var mUpdateHandler: android.os.Handler? = null
    private var mUpdateRunnable: Runnable? = null
    private var mOffsetMs: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 从 Intent 获取数据
        mTimeSource = intent.getStringExtra(EXTRA_TIME_SOURCE) ?: "悬浮时间"
        
        // 设置透明主题以匹配 Bubble 样式
        setContentView(R.layout.activity_bubble)
        
        // 初始化视图
        initViews()
        
        // 开始更新时间
        startUpdating()
        
        Log.d(TAG, "BubbleActivity created")
    }

    private fun initViews() {
        mTimeText = findViewById(R.id.bubble_time)
        mMillisText = findViewById(R.id.bubble_millis)
        mSourceText = findViewById(R.id.bubble_source)
        
        mSourceText.text = mTimeSource
        mTimeText.text = "--:--:--"
        mMillisText.text = ".000"
        
        // 点击关闭按钮
        findViewById<View>(R.id.bubble_close)?.setOnClickListener {
            finish()
        }
        
        // 点击主区域打开主界面
        findViewById<View>(R.id.bubble_container)?.setOnClickListener {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(mainIntent)
            finish()
        }
    }

    private fun startUpdating() {
        mUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
        
        mUpdateRunnable = object : Runnable {
            private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            
            override fun run() {
                try {
                    val now = System.currentTimeMillis() + mOffsetMs
                    val timeStr = timeFormat.format(Date(now))
                    val millisStr = String.format(Locale.getDefault(), ".%03d", now % 1000)
                    
                    mTimeText.text = timeStr
                    mMillisText.text = millisStr
                    
                    // 每100ms更新一次
                    mUpdateHandler?.postDelayed(this, 100)
                } catch (e: Exception) {
                    Log.e(TAG, "Update error: ${e.message}")
                }
            }
        }
        
        mUpdateHandler?.post(mUpdateRunnable!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        mUpdateRunnable?.let { mUpdateHandler?.removeCallbacks(it) }
        Log.d(TAG, "BubbleActivity destroyed")
    }
}

package com.floattime.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 圆环进度自定义视图
 * 橙色渐变圆环 + 中心时间文字
 */
class CircularProgressView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f
        strokeCap = Paint.Cap.ROUND
    }

    private val bgRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f
        color = 0xFFF0E6DE.toInt()  // warm light background ring
    }

    private val centerTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 72f
        color = 0xFFFF6B35.toInt()  // orange
        isFakeBoldText = true
    }

    private val centerLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 28f
        color = 0xFFFF6B35.toInt()
        alpha = 180
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFF6B35.toInt()
    }

    private val innerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFBF7.toInt()
    }

    private val rect = RectF()
    private var progress: Float = 0f          // 0..1
    private var animatedProgress: Float = 0f  // current animated value
    private var dotPhase: Float = 0f
    private var centerTime: String = ""
    private var animator: ValueAnimator? = null

    fun setProgress(percent: Float) {
        val target = percent.coerceIn(0f, 100f) / 100f
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animatedProgress, target).apply {
            duration = 1200
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                animatedProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        progress = target
    }

    fun setCenterTime(time: String) {
        if (centerTime != time) {
            centerTime = time
            invalidate()
        }
    }

    fun startDotAnimation() {
        val anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 4000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                dotPhase = it.animatedValue as Float
                invalidate()
            }
        }
        anim.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val strokeHalf = bgRingPaint.strokeWidth / 2f
        val radius = minOf(w, h) / 2f - strokeHalf
        val cx = w / 2f
        val cy = h / 2f

        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Background ring
        canvas.drawArc(rect, 0f, 360f, false, bgRingPaint)

        // Orange gradient ring (progress)
        if (animatedProgress > 0.001f) {
            val colors = intArrayOf(0xFFFF9A5C.toInt(), 0xFFFF6B35.toInt(), 0xFFE85D26.toInt())
            val positions = floatArrayOf(0f, 0.5f, 1f)
            ringPaint.shader = SweepGradient(cx, cy, colors, positions)
            canvas.drawArc(rect, -90f, animatedProgress * 360f, false, ringPaint)
        }

        // Inner circle
        val innerRadius = radius * 0.70f
        canvas.drawCircle(cx, cy, innerRadius, innerCirclePaint)

        // Center time text
        if (centerTime.isNotEmpty()) {
            val timeBounds = android.graphics.Rect()
            centerTimePaint.getTextBounds(centerTime, 0, centerTime.length, timeBounds)
            canvas.drawText(centerTime, cx, cy + timeBounds.height() / 2f - 8f, centerTimePaint)
        }
    }
}

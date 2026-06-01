package com.rokuonsumm.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class TimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var dayStartMs = 0L
    private var dayEndMs   = 0L
    private var segments: List<Pair<Long, Long>> = emptyList()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF.toInt() }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB71C1C.toInt() }

    private val trackRect = RectF()
    private val segRect   = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 3f * resources.displayMetrics.density
        if (w == 0f || h == 0f || dayEndMs <= dayStartMs) return

        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, r, r, bgPaint)

        val span = (dayEndMs - dayStartMs).toFloat()
        for ((start, end) in segments) {
            val x0 = ((start - dayStartMs) / span * w).coerceIn(0f, w)
            val x1 = ((end   - dayStartMs) / span * w).coerceIn(0f, w)
            if (x1 <= x0) continue
            segRect.set(x0, 0f, x1, h)
            canvas.drawRoundRect(segRect, r, r, segPaint)
        }
    }

    fun setData(dayStartMs: Long, dayEndMs: Long, segments: List<Pair<Long, Long>>) {
        this.dayStartMs = dayStartMs
        this.dayEndMs   = dayEndMs
        this.segments   = segments
        invalidate()
    }
}

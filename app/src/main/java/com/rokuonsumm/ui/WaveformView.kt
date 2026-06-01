package com.rokuonsumm.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.rokuonsumm.recording.AudioAnalyzer

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val voicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4CAF50.toInt()
    }
    private val silencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2D2D2D.toInt()
    }
    private val loadingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3A.toInt()
    }
    private val cursorPaint = Paint().apply {
        color = 0xEEFFFFFF.toInt()
        strokeWidth = 2.5f
    }

    private var amps = FloatArray(0)
    private var posFraction = 0f
    private var isLoading = false
    private var maxAmp = 1f

    var onSeek: ((Float) -> Unit)? = null

    fun setAmplitudes(data: FloatArray) {
        amps = data
        maxAmp = if (data.isNotEmpty()) data.max().coerceAtLeast(0.001f) else 1f
        isLoading = false
        invalidate()
    }

    fun setLoading() {
        isLoading = true
        amps = FloatArray(0)
        posFraction = 0f
        invalidate()
    }

    fun setPosFraction(f: Float) {
        posFraction = f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f

        if (isLoading || amps.isEmpty()) {
            // flat loading bar
            canvas.drawRect(0f, midY - 2f, w, midY + 2f, loadingPaint)
        } else {
            val barW = w / amps.size
            amps.forEachIndexed { i, amp ->
                val ratio = (amp / maxAmp).coerceAtLeast(0.04f)
                val barH = ratio * (midY - 1f)
                val x = i * barW
                val paint = if (amp > AudioAnalyzer.SILENCE_THRESHOLD) voicePaint else silencePaint
                canvas.drawRect(x, midY - barH, x + barW - 0.5f, midY + barH, paint)
            }
        }

        // Playhead
        val cx = posFraction * w
        canvas.drawLine(cx, 0f, cx, h, cursorPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            onSeek?.invoke((e.x / width).coerceIn(0f, 1f))
            return true
        }
        return false
    }
}

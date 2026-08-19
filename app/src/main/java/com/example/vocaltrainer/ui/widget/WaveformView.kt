package com.example.vocaltrainer.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.vocaltrainer.R

/** Zeichnet eine einfache, aus Peak-Buckets gewonnene Wellenform + Wiedergabe-Fortschritt. */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var peaks: FloatArray = FloatArray(0)
    private var progress: Float = 0f

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.waveform)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.waveformProgress)
    }

    fun setPeaks(newPeaks: FloatArray) {
        peaks = newPeaks
        progress = 0f
        invalidate()
    }

    fun setProgress(fraction: Float) {
        progress = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (peaks.isEmpty()) return

        val w = width.toFloat()
        val midY = height / 2f
        val barWidth = w / peaks.size
        val progressX = w * progress

        for (i in peaks.indices) {
            val barHeight = peaks[i] * midY
            val left = i * barWidth
            val right = left + barWidth * 0.8f
            val paint = if (left <= progressX) progressPaint else barPaint
            canvas.drawRect(left, midY - barHeight, right, midY + barHeight, paint)
        }
    }
}

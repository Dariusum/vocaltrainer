package com.example.vocaltrainer.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.vocaltrainer.R

/**
 * Zeichnet eine einfache, aus Peak-Buckets gewonnene Wellenform + Wiedergabe-Fortschritt.
 * Tippen oder Wischen setzt die angezeigte Position sofort (visuelles Feedback beim Ziehen),
 * löst den tatsächlichen Sprung im Player aber erst beim Loslassen aus — ein Sprung pro
 * Bewegungsereignis wäre hörbar unruhig, da [com.example.vocaltrainer.audio.LivePlaybackEngine]
 * Seeking über einen Stop+Neustart an der Zielposition umsetzt, nicht über echtes In-Place-Seeking.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var peaks: FloatArray = FloatArray(0)
    private var progress: Float = 0f
    private var onSeek: ((Float) -> Unit)? = null

    fun setOnSeekListener(listener: (Float) -> Unit) {
        onSeek = listener
    }

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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (peaks.isEmpty() || width == 0) return false
        val fraction = (event.x / width.toFloat()).coerceIn(0f, 1f)
        return when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                progress = fraction
                invalidate()
                true
            }
            MotionEvent.ACTION_UP -> {
                progress = fraction
                invalidate()
                onSeek?.invoke(fraction)
                true
            }
            else -> super.onTouchEvent(event)
        }
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

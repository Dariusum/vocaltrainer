package com.example.vocaltrainer.audio

import kotlin.math.cos
import kotlin.math.sin

/**
 * Einfaches IIR-Biquad-Filter (Direct Form I), Standardformeln aus dem
 * "Audio EQ Cookbook" (Robert Bristow-Johnson). Hält eigenen Zustand
 * (letzte Ein-/Ausgabewerte), muss also pro Signalkette einmal erzeugt und
 * dann fortlaufend mit aufeinanderfolgenden Samples gefüttert werden.
 */
class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x0: Double): Double {
        val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x0
        y2 = y1
        y1 = y0
        return y0
    }

    companion object {
        fun highPass(sampleRate: Int, cutoffHz: Double, q: Double = 0.707): Biquad {
            val w0 = 2.0 * Math.PI * cutoffHz / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosw0 = cos(w0)
            val a0 = 1 + alpha
            return Biquad(
                b0 = ((1 + cosw0) / 2) / a0,
                b1 = (-(1 + cosw0)) / a0,
                b2 = ((1 + cosw0) / 2) / a0,
                a1 = (-2 * cosw0) / a0,
                a2 = (1 - alpha) / a0
            )
        }

        fun lowPass(sampleRate: Int, cutoffHz: Double, q: Double = 0.707): Biquad {
            val w0 = 2.0 * Math.PI * cutoffHz / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosw0 = cos(w0)
            val a0 = 1 + alpha
            return Biquad(
                b0 = ((1 - cosw0) / 2) / a0,
                b1 = (1 - cosw0) / a0,
                b2 = ((1 - cosw0) / 2) / a0,
                a1 = (-2 * cosw0) / a0,
                a2 = (1 - alpha) / a0
            )
        }
    }
}

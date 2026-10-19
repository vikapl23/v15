package com.lab.vibrationanalyzer.analysis

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Імітація зносу обладнання (для варіанта "Smart Vibration Analyzer").
 *
 * Додає до реальних даних акселерометра синтетичну вібрацію:
 * гармоніку частотою [frequencyHz] (дисбаланс ротора) та випадковий шум
 * (люфт підшипника). Амплітуда пропорційна рівню зносу 0..1.
 * Коли імітацію вимкнено, додаток працює лише з даними сенсора.
 */
class WearSimulator(
    private val frequencyHz: Float = 7f,
    private val maxAmplitude: Float = 3f,   // м/с² при зносі 100 %
    private val random: Random = Random(42)
) {
    /** Повертає добавку до осей X, Y, Z для моменту часу [timestampNs]. */
    fun offset(wearLevel: Float, timestampNs: Long): FloatArray {
        if (wearLevel <= 0f) return floatArrayOf(0f, 0f, 0f)
        val t = timestampNs / 1_000_000_000.0
        val amplitude = maxAmplitude * wearLevel.coerceIn(0f, 1f)
        val harmonic = (sin(2 * PI * frequencyHz * t) * amplitude).toFloat()
        fun noise() = (random.nextFloat() * 2f - 1f) * amplitude * 0.5f
        return floatArrayOf(harmonic + noise(), harmonic * 0.5f + noise(), noise())
    }
}

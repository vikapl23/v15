package com.lab.vibrationanalyzer.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** ЛР6. Патерни вібрації, які можна «підмішати» до сенсора, щоб показати роботу моделі в емуляторі. */
enum class DemoPattern(val title: String) {
    NONE("Вимкнено"),
    TOUCH("Дотик"),
    MACHINE("Робоча вібрація"),
    TAMPER("Спроба злому")
}

/**
 * ЛР6. Генератор демонстраційних патернів (як WearSimulator з ЛР1, але для класів моделі).
 *
 * В емуляторі важко руками відтворити серію ударів, тому додаток може додати до даних сенсора:
 *  - TOUCH   — одиночний затухаючий поштовх раз на 3 с;
 *  - MACHINE — рівномірна гармоніка 6 Гц, 1.2 м/с² (працює обладнання);
 *  - TAMPER  — нерегулярні сильні удари 2.5–6 м/с² кожні 80–350 мс + широкосмуговий шум (свердління).
 */
class PatternSimulator(private val random: Random = Random(15)) {

    private class Impact(val t0: Double, val amp: Float, val dir: FloatArray)

    private val impacts = ArrayDeque<Impact>()
    private var nextImpactT = Double.NaN
    private var lastT = Double.NaN

    fun offset(pattern: DemoPattern, timestampNs: Long): FloatArray {
        val t = timestampNs / 1_000_000_000.0
        if (!lastT.isNaN() && t < lastT) reset()   // час «пішов назад» — нова сесія сенсора
        lastT = t
        return when (pattern) {
            DemoPattern.NONE -> floatArrayOf(0f, 0f, 0f)
            DemoPattern.TOUCH -> {
                val t0 = Math.floorDiv(timestampNs, TOUCH_PERIOD_NS) * TOUCH_PERIOD_NS / 1e9
                val v = damped(t - t0, TOUCH_AMP, 0.04, 15.0)
                floatArrayOf(v * 0.8f, v * 0.5f, v * -0.33f)
            }
            DemoPattern.MACHINE -> {
                val h = (sin(2 * PI * 6.0 * t) * 1.2).toFloat()
                floatArrayOf(h * 0.8f + noise(0.08f), h * 0.5f + noise(0.08f), h * 0.3f + noise(0.08f))
            }
            DemoPattern.TAMPER -> tamper(t)
        }
    }

    private fun tamper(t: Double): FloatArray {
        if (nextImpactT.isNaN()) nextImpactT = t
        while (t >= nextImpactT) {
            impacts.addLast(Impact(nextImpactT, 2.5f + random.nextFloat() * 3.5f, randomDirection()))
            nextImpactT += 0.08 + random.nextDouble() * 0.27
        }
        while (impacts.isNotEmpty() && t - impacts.first().t0 > 0.3) impacts.removeFirst()
        val out = FloatArray(3) { gaussian() * 0.35f }
        for (impact in impacts) {
            val v = damped(t - impact.t0, impact.amp, 0.03, 15.0)
            for (i in 0..2) out[i] += impact.dir[i] * v
        }
        return out
    }

    fun reset() {
        impacts.clear()
        nextImpactT = Double.NaN
        lastT = Double.NaN
    }

    /** Затухаючий імпульс (удар): A·e^(−t/τ)·cos(2πft). */
    private fun damped(dt: Double, amp: Float, tau: Double, f: Double): Float =
        if (dt < 0 || dt > 6 * tau) 0f else (amp * exp(-dt / tau) * cos(2 * PI * f * dt)).toFloat()

    private fun noise(a: Float) = (random.nextFloat() * 2f - 1f) * a

    /** Нормальний розподіл N(0,1) методом Бокса — Мюллера. */
    private fun gaussian(): Float {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return (sqrt(-2 * ln(u1)) * cos(2 * PI * u2)).toFloat()
    }

    private fun randomDirection(): FloatArray {
        val v = FloatArray(3) { gaussian() }
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-6f)
        return FloatArray(3) { v[it] / n }
    }

    private companion object {
        const val TOUCH_PERIOD_NS = 3_000_000_000L
        const val TOUCH_AMP = 4f
    }
}

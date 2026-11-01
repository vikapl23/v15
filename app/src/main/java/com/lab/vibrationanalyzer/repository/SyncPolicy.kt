package com.lab.vibrationanalyzer.repository

import kotlin.math.abs
import kotlin.math.max

/**
 * ЛР3. Правило «коли відправляти». Акселерометр дає ~50 відліків за секунду,
 * а в хмару треба слати рідко, щоб не перевантажувати мережу.
 *
 * Відправляємо, якщо виконується хоча б одна умова:
 *  1) минуло [intervalMs] від останньої відправки (за замовчуванням 5 с);
 *  2) значення змінилось на Δ ([delta]) або більше: RMS чи будь-яка з координат X/Y/Z.
 *     Але не частіше ніж раз на [minGapMs], щоб різкий рух не дав 50 запитів за секунду.
 *
 * Клас не залежить від Android, тому перевіряється звичайними JUnit-тестами.
 */
class SyncPolicy(
    var intervalMs: Long = 5_000L,
    var delta: Float = 1.0f,
    val minGapMs: Long = 1_000L
) {
    private var last: SensorData? = null
    private var lastAtMs: Long = 0L

    /** Причина відправки — показуємо її в журналі на екрані «Хмара». */
    enum class Reason(val title: String) { FIRST("перший"), INTERVAL("таймер"), DELTA("зміна Δ") }

    /** Повертає причину відправки або null, якщо цей відлік слати не треба. */
    fun check(data: SensorData, nowMs: Long): Reason? {
        val prev = last ?: return Reason.FIRST
        val elapsed = nowMs - lastAtMs
        if (elapsed >= intervalMs) return Reason.INTERVAL
        if (elapsed >= minGapMs && changeOf(prev, data) >= delta) return Reason.DELTA
        return null
    }

    /** Запам'ятати, що цей відлік відправлено (або поставлено в чергу Pending). */
    fun markSent(data: SensorData, nowMs: Long) {
        last = data
        lastAtMs = nowMs
    }

    private fun changeOf(a: SensorData, b: SensorData): Float = max(
        abs(a.value - b.value),
        max(abs(a.x - b.x), max(abs(a.y - b.y), abs(a.z - b.z)))
    )
}

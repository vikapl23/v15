package com.lab.vibrationanalyzer.analysis

/** Одна точка лінійного графіка: X — час, Y — значення сенсора. */
data class ChartPoint(
    val timestampMs: Long,
    val value: Float,
    val triggered: Boolean
)

/**
 * ЛР5. Зменшення кількості точок графіка.
 *
 * За годину в БД набирається кілька тисяч записів, а на екрані телефону
 * однаково видно не більше ~50–60 точок: решта лише навантажує рендеринг.
 * Тому ділимо ряд на [maxPoints] рівних груп і з кожної беремо ОДНУ точку —
 * з найбільшим значенням (для вібрації важливі піки, а не середнє:
 * усереднення «з'їло» б короткий удар). Якщо в групі спрацював актуатор,
 * точка позначається як triggered.
 */
object ChartDownsampler {

    fun downsample(points: List<ChartPoint>, maxPoints: Int): List<ChartPoint> {
        require(maxPoints > 0) { "maxPoints має бути > 0" }
        if (points.size <= maxPoints) return points
        val result = ArrayList<ChartPoint>(maxPoints)
        for (bucket in 0 until maxPoints) {
            // Межі групи: [from, to). Цілочисельне ділення рівномірно розподіляє залишок.
            val from = bucket * points.size / maxPoints
            val to = (bucket + 1) * points.size / maxPoints
            if (from >= to) continue
            val group = points.subList(from, to)
            val peak = group.maxBy { it.value }
            result += peak.copy(triggered = group.any { it.triggered })
        }
        return result
    }
}

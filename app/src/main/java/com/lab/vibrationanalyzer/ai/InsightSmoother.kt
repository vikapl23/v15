package com.lab.vibrationanalyzer.ai

/**
 * ЛР6. Згладжування прогнозів і рішення «аномалія / висока активність».
 *
 * Один прогноз може помилитися (наприклад, перший удар серії модель бачить як «дотик»).
 * Тому:
 *  1. Усереднюємо ймовірності останніх [historySize] прогнозів (≈ 0.75 с при 4 прогнозах/с).
 *  2. Гістерезис: прапорець вмикається, коли ймовірність ≥ [enter], а вимикається лише < [exit].
 *     Без цього сирена «брязкала» б на межі (як і поріг у DecisionEngine з ЛР4).
 *
 * Чистий Kotlin — перевіряється юніт-тестами.
 */
class InsightSmoother(
    private val historySize: Int = 3,
    private val enter: Float = 0.6f,
    private val exit: Float = 0.4f
) {
    private val classes = VibrationClass.entries
    private val history = ArrayDeque<FloatArray>()
    private var anomaly = false
    private var highActivity = false
    private var count = 0L

    fun update(probabilities: FloatArray, inferenceMs: Float, nowMs: Long): AiInsight {
        require(probabilities.size == classes.size) {
            "модель повернула ${probabilities.size} ймовірностей, очікувалось ${classes.size}"
        }
        history.addLast(probabilities.copyOf())
        if (history.size > historySize) history.removeFirst()

        val avg = FloatArray(classes.size) { i -> history.map { it[i] }.average().toFloat() }
        val tamper = avg[VibrationClass.TAMPER.ordinal]
        val active = avg[VibrationClass.MACHINE.ordinal] + tamper

        anomaly = if (anomaly) tamper >= exit else tamper >= enter
        highActivity = if (highActivity) active >= exit else active >= enter

        val top = argmax(avg)
        count++
        return AiInsight(
            top = classes[top],
            confidence = avg[top],
            probabilities = classes.associateWith { avg[it.ordinal] },
            rawTop = classes[argmax(probabilities)],
            anomaly = anomaly,
            highActivity = highActivity,
            inferenceMs = inferenceMs,
            timestampMs = nowMs,
            count = count
        )
    }

    fun reset() {
        history.clear()
        anomaly = false
        highActivity = false
    }

    private fun argmax(a: FloatArray): Int = a.indices.maxBy { a[it] }
}

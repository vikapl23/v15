package com.lab.vibrationanalyzer.ai

/**
 * ЛР6. Результат «розумного» аналізу, який бачать інші частини системи.
 *
 * @param top найімовірніший клас (після згладжування)
 * @param confidence його ймовірність 0..1
 * @param probabilities згладжені ймовірності всіх класів
 * @param rawTop клас з останнього прогнозу без згладжування
 * @param anomaly «спроба злому» стабільно детектується → вмикаємо актуатор (ЛР4)
 * @param highActivity робоча вібрація або злом → прискорюємо запис у БД (ЛР5)
 */
data class AiInsight(
    val top: VibrationClass,
    val confidence: Float,
    val probabilities: Map<VibrationClass, Float>,
    val rawTop: VibrationClass,
    val anomaly: Boolean,
    val highActivity: Boolean,
    val inferenceMs: Float,
    val timestampMs: Long,
    val count: Long
) {
    /** Рядок як у методичці: "Current Activity: Walking (92%)". */
    val headline: String get() = "${top.title} (${Math.round(confidence * 100)}%)"
}

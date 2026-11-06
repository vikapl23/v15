package com.lab.vibrationanalyzer.ai

// State блоку «AI Insights» на головному екрані (ЛР6). Тільки val, значення за замовчуванням.
data class AiState(
    val enabled: Boolean = true,              // перемикач «ШІ-аналіз»
    val modelLoaded: Boolean = false,
    val modelDescription: String = "",
    val error: String? = null,                // якщо модель не завантажилась
    val bufferedFrames: Int = 0,              // заповнення кільцевого буфера
    val windowFrames: Int = ModelSpec.WINDOW,
    val insight: AiInsight? = null,           // останній (згладжений) прогноз
    val avgInferenceMs: Float = 0f,
    val thread: String = ""                   // у якому потоці виконався inference (для звіту)
)

sealed interface AiAction {
    data class EnabledToggled(val enabled: Boolean) : AiAction
}

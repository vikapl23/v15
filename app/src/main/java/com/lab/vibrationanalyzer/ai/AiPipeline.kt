package com.lab.vibrationanalyzer.ai

/**
 * ЛР6. «Конвеєр» підготовки даних: відлік сенсора → кадр → кільцевий буфер → вікно для моделі.
 *
 * Повертає вікно (FloatArray із 60 чисел), коли настав час робити прогноз:
 * буфер повний (є цілих 20 кадрів = 1 с) і від попереднього прогнозу додалось [hopFrames] кадрів.
 * Так модель викликається 4 рази на секунду, а не 50 — inference не перевантажує CPU.
 */
class AiPipeline(
    private val preprocessor: WindowPreprocessor = WindowPreprocessor(),
    windowFrames: Int = ModelSpec.WINDOW,
    private val hopFrames: Int = ModelSpec.HOP_FRAMES
) {
    private val buffer = CircularBuffer(windowFrames, ModelSpec.CHANNELS)
    private var framesSinceInference = 0

    /** Скільки кадрів уже накопичено (для індикатора «буфер 12/20»). */
    val bufferedFrames: Int get() = buffer.size
    val windowFrames: Int get() = buffer.capacity

    fun push(x: Float, y: Float, z: Float, timeMs: Long): FloatArray? {
        val frame = preprocessor.push(x, y, z, timeMs) ?: return null
        buffer.add(frame)
        framesSinceInference++
        if (!buffer.isFull || framesSinceInference < hopFrames) return null
        framesSinceInference = 0
        return buffer.toFloatArray()
    }

    fun reset() {
        preprocessor.reset()
        buffer.clear()
        framesSinceInference = 0
    }
}

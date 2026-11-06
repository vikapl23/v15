package com.lab.vibrationanalyzer.ai

import kotlin.math.sqrt

/**
 * ЛР6. Перетворення «сирих» відліків акселерометра на кадри для моделі.
 *
 * Сенсор дає ~50 відліків/с з гравітацією (~9.81 м/с²) всередині. Модель навчалась на
 * 20 кадрах за секунду динамічного прискорення, тому для кожного відліку:
 *  1. Фільтр низьких частот виділяє гравітацію: g = α·g + (1−α)·a  (α = 0.8, як у ЛР1).
 *  2. Динамічне прискорення: d = a − g (те, що залишилось після прибирання гравітації).
 *  3. Відліки групуються в кадри по 50 мс. З кадру береться відлік з НАЙБІЛЬШИМ |d| —
 *     так короткий удар (1 відлік) не губиться при проріджуванні 50 Гц → 20 Гц.
 *  4. Нормування: d / 4 м/с², обрізання до ±3.
 *
 * Чистий Kotlin без Android — перевіряється юніт-тестом на контрольних векторах,
 * згенерованих тим самим кодом на Python (ml/train_vibration_model.py).
 */
class WindowPreprocessor(
    private val frameMs: Long = ModelSpec.FRAME_MS,
    private val alpha: Float = ModelSpec.ALPHA,
    private val scale: Float = ModelSpec.SCALE,
    private val clip: Float = ModelSpec.CLIP
) {
    private val gravity = FloatArray(3)
    private var gravityReady = false
    private var frameStartMs = NO_FRAME
    private val peak = FloatArray(3)
    private var peakMagnitude = -1f

    /**
     * Додає один відлік (м/с², час у мс).
     * @return готовий кадр [dx, dy, dz] (нормований), якщо щойно завершився кадр 50 мс; інакше null.
     */
    fun push(x: Float, y: Float, z: Float, timeMs: Long): FloatArray? {
        // 1. Гравітація (перший відлік приймаємо за гравітацію — без хибного піку на старті)
        if (!gravityReady) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            gravityReady = true
        } else {
            gravity[0] = alpha * gravity[0] + (1 - alpha) * x
            gravity[1] = alpha * gravity[1] + (1 - alpha) * y
            gravity[2] = alpha * gravity[2] + (1 - alpha) * z
        }
        // 2. Динамічне прискорення
        val dx = x - gravity[0]
        val dy = y - gravity[1]
        val dz = z - gravity[2]
        val magnitude = sqrt(dx * dx + dy * dy + dz * dz)

        // 3. Чи завершився поточний кадр?
        var frame: FloatArray? = null
        if (frameStartMs == NO_FRAME) {
            frameStartMs = timeMs
        } else if (timeMs - frameStartMs >= frameMs) {
            frame = FloatArray(3) { i -> (peak[i] / scale).coerceIn(-clip, clip) }   // 4. нормування
            frameStartMs += frameMs
            // великий пропуск даних (пауза сенсора) — починаємо кадри заново від поточного часу
            if (timeMs - frameStartMs >= frameMs) frameStartMs = timeMs
            peakMagnitude = -1f
        }
        if (magnitude > peakMagnitude) {
            peak[0] = dx; peak[1] = dy; peak[2] = dz
            peakMagnitude = magnitude
        }
        return frame
    }

    fun reset() {
        gravityReady = false
        frameStartMs = NO_FRAME
        peakMagnitude = -1f
    }

    private companion object {
        const val NO_FRAME = Long.MIN_VALUE
    }
}

package com.lab.vibrationanalyzer.repository

/**
 * ЛР3. Модель даних, яка йде в хмару. Gson перетворює її на JSON, наприклад:
 *
 * {
 *   "deviceId": "vibro-15",
 *   "timestamp": 1761999000000,
 *   "type": "accelerometer",
 *   "value": 0.42,
 *   "x": 0.13, "y": 9.71, "z": 1.02,
 *   "state": "ELEVATED",
 *   "wearPercent": 3.5
 * }
 *
 * Обов'язкові поля з методички: id пристрою, timestamp, value, type.
 * Поля x, y, z — це SensorPayload з прикладу викладача (координати акселерометра).
 * value — RMS вібрації (м/с²), головна величина нашого аналізатора.
 */
data class SensorData(
    val deviceId: String,
    val timestamp: Long,
    val type: String,
    val value: Float,
    val x: Float,
    val y: Float,
    val z: Float,
    val state: String,
    val wearPercent: Float
) {
    companion object {
        const val TYPE_ACCELEROMETER = "accelerometer"
    }
}

/** Перетворення відліку з репозиторію (ЛР2) на модель для хмари. */
fun VibrationReading.toSensorData(deviceId: String) = SensorData(
    deviceId = deviceId,
    timestamp = timestampMs,
    type = SensorData.TYPE_ACCELEROMETER,
    value = rms.round2(),
    x = x.round2(),
    y = y.round2(),
    z = z.round2(),
    state = state.name,
    wearPercent = wearPercent.round2()
)

/** Округлення до 0.01: у консолі Firebase читається краще, ніж 0.41999998. */
private fun Float.round2(): Float = Math.round(this * 100f) / 100f

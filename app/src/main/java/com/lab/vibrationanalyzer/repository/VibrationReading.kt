package com.lab.vibrationanalyzer.repository

import com.lab.vibrationanalyzer.analysis.VibrationSnapshot
import com.lab.vibrationanalyzer.analysis.VibrationState

/** Модель даних, з якою працюють ViewModel: короткий підсумок одного відліку. */
data class VibrationReading(
    val rms: Float,           // м/с²
    val peak: Float,          // м/с²
    val state: VibrationState,
    val wearPercent: Float,   // %
    val timestampMs: Long,
    // ЛР3: сирі координати акселерометра, щоб у хмарі було видно положення пристрою
    val x: Float = 0f,        // м/с²
    val y: Float = 0f,        // м/с²
    val z: Float = 0f,        // м/с²
    // ЛР6: час події сенсора (SensorEvent.timestamp, переведений у мс) — для кадрів моделі
    val sensorTimeMs: Long = timestampMs
)

/** Перетворення результату аналізу на модель репозиторію. */
fun VibrationSnapshot.toReading(
    timestampMs: Long = System.currentTimeMillis(),
    sensorTimeMs: Long = timestampMs
) = VibrationReading(
    rms = rms,
    peak = peak,
    state = state,
    wearPercent = wearPercent,
    timestampMs = timestampMs,
    x = x,
    y = y,
    z = z,
    sensorTimeMs = sensorTimeMs
)

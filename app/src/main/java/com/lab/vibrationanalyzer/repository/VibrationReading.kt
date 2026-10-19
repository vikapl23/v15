package com.lab.vibrationanalyzer.repository

import com.lab.vibrationanalyzer.analysis.VibrationSnapshot
import com.lab.vibrationanalyzer.analysis.VibrationState

/** Модель даних, з якою працюють ViewModel: короткий підсумок одного відліку. */
data class VibrationReading(
    val rms: Float,           // м/с²
    val peak: Float,          // м/с²
    val state: VibrationState,
    val wearPercent: Float,   // %
    val timestampMs: Long
)

/** Перетворення результату аналізу на модель репозиторію. */
fun VibrationSnapshot.toReading(timestampMs: Long = System.currentTimeMillis()) = VibrationReading(
    rms = rms,
    peak = peak,
    state = state,
    wearPercent = wearPercent,
    timestampMs = timestampMs
)

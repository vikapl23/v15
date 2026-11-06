package com.lab.vibrationanalyzer.analytics

import com.lab.vibrationanalyzer.analysis.ChartPoint
import com.lab.vibrationanalyzer.analysis.RecordingMode
import com.lab.vibrationanalyzer.analysis.VibrationState
import com.lab.vibrationanalyzer.repository.local.RecordStats

/** Що показує графік. */
enum class ChartRange(val title: String) {
    LAST_N("Останні ${SensorViewModel.CHART_POINTS}"),   // кожен запис окремою точкою, у реальному часі
    LAST_HOUR("Остання година")                          // уся година, стиснута до 60 точок
}

// State — усе, що видно на екрані «Аналітика» (ЛР5). Тільки val, значення за замовчуванням.
data class SensorState(
    // Запис у БД
    val recordingEnabled: Boolean = true,
    val mode: RecordingMode = RecordingMode.ON_CHANGE,
    val liveValue: Float? = null,          // останнє значення з сенсора (ще не обов'язково в БД)
    val actuatorActive: Boolean = false,   // стан актуаторів ЛР4 → поле actionTriggered
    val savedThisSession: Int = 0,
    val lastSavedAtMs: Long? = null,
    // Дані з БД
    val range: ChartRange = ChartRange.LAST_N,
    val points: List<ChartPoint> = emptyList(),
    val totalRecords: Int = 0,
    val hourStats: RecordStats = RecordStats.EMPTY,
    val triggeredHistory: List<ChartPoint> = emptyList(),
    // ЛР6: модель ШІ прискорила запис (робоча вібрація / злом)
    val aiBoosted: Boolean = false,
    val aiClass: String? = null,
    val writeIntervalMs: Long = 200L,     // поточний мінімальний інтервал запису
    // Лінія порогу на графіку — межа «Критична» з ЛР1
    val threshold: Float = VibrationState.CRITICAL_THRESHOLD
)

// Action — дії користувача на екрані «Аналітика».
sealed interface SensorAction {
    data class RecordingToggled(val enabled: Boolean) : SensorAction
    data class ModeChanged(val mode: RecordingMode) : SensorAction
    data class RangeChanged(val range: ChartRange) : SensorAction
    data object ClearClick : SensorAction
}

// Event — разові повідомлення.
sealed interface SensorEvent {
    data class ShowMessage(val text: String) : SensorEvent
}

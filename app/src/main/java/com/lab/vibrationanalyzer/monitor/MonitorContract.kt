package com.lab.vibrationanalyzer.monitor

import com.lab.vibrationanalyzer.analysis.DemoPattern
import com.lab.vibrationanalyzer.analysis.VibrationSnapshot
import com.lab.vibrationanalyzer.repository.SyncStatus

// State — усе, що видно на екрані «Монітор». Тільки val, значення за замовчуванням.
data class MonitorState(
    val sensorAvailable: Boolean = true,
    val isRunning: Boolean = false,
    val snapshot: VibrationSnapshot? = null,
    val simulationEnabled: Boolean = false,
    val simulatedWear: Float = 0.3f,  // рівень імітованого зносу 0..1
    // ЛР3: індикатор «Хмарна синхронізація» (зелений/червоний)
    val cloudStatus: SyncStatus = SyncStatus.IDLE,
    val cloudOk: Boolean? = null,
    // ЛР6: демонстраційний патерн для перевірки моделі в емуляторі
    val demoPattern: DemoPattern = DemoPattern.NONE
)

// Action — усі дії користувача (і життєвого циклу) на цьому екрані.
sealed interface MonitorAction {
    data object Start : MonitorAction                       // onResume
    data object Stop : MonitorAction                        // onPause
    data class SimulationToggled(val enabled: Boolean) : MonitorAction
    data class WearLevelChanged(val level: Float) : MonitorAction
    data object ResetWearClick : MonitorAction
    data class DemoPatternChanged(val pattern: DemoPattern) : MonitorAction   // ЛР6
}

// Event — разові повідомлення від ViewModel до екрана.
sealed interface MonitorEvent {
    data class ShowMessage(val text: String) : MonitorEvent
}

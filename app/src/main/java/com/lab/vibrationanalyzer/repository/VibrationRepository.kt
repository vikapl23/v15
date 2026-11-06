package com.lab.vibrationanalyzer.repository

import com.lab.vibrationanalyzer.ai.AiInsight
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Одне місце для «живих» даних вібрації (у пам'яті).
 *
 * ЛР2: MonitorViewModel публікує останній відлік, HubViewModel і ControlViewModel його читають.
 * ЛР5: ControlViewModel публікує ще й стан актуаторів, а SensorViewModel бере обидва
 * значення і пише їх у локальну БД Room (SensorRecordRepository).
 * ЛР6: [samples] — КОЖЕН відлік без пропусків (для вікна моделі), [aiInsight] — прогноз моделі.
 */
class VibrationRepository {

    private val _latest = MutableStateFlow<VibrationReading?>(null)
    val latest: StateFlow<VibrationReading?> = _latest.asStateFlow()

    // ЛР6: StateFlow віддає лише ОСТАННЄ значення і може пропускати проміжні.
    // Моделі потрібне неперервне вікно, тому окремий SharedFlow з буфером на 64 відліки (~1.3 с).
    private val _samples = MutableSharedFlow<VibrationReading>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val samples: SharedFlow<VibrationReading> = _samples.asSharedFlow()

    // ЛР5: чи працює зараз хоча б один актуатор (сирена або прожектор) з ЛР4
    private val _actuatorActive = MutableStateFlow(false)
    val actuatorActive: StateFlow<Boolean> = _actuatorActive.asStateFlow()

    // ЛР6: останній прогноз моделі (null — ШІ вимкнено або ще немає повного вікна)
    private val _aiInsight = MutableStateFlow<AiInsight?>(null)
    val aiInsight: StateFlow<AiInsight?> = _aiInsight.asStateFlow()

    fun publish(reading: VibrationReading) {
        _latest.value = reading
        _samples.tryEmit(reading)
    }

    fun publishActuatorState(active: Boolean) {
        _actuatorActive.value = active
    }

    fun publishAiInsight(insight: AiInsight?) {
        _aiInsight.value = insight
    }
}

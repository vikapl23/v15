package com.lab.vibrationanalyzer.monitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lab.vibrationanalyzer.SmartVibrationApp
import com.lab.vibrationanalyzer.analysis.VibrationAnalyzer
import com.lab.vibrationanalyzer.analysis.WearSimulator
import com.lab.vibrationanalyzer.helpers.AccelSample
import com.lab.vibrationanalyzer.helpers.SensorReader
import com.lab.vibrationanalyzer.repository.toReading
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * «Мозок» екрана Монітор: отримує Action, читає сенсор через помічник,
 * аналізує дані і публікує результат у State та в репозиторій.
 * ViewModel переживає поворот екрана, тому ковзне вікно і знос не губляться.
 */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val sensorReader = SensorReader(app)
    private val repository = (app as SmartVibrationApp).repository
    private val cloudRepository = (app as SmartVibrationApp).cloudRepository   // ЛР3: лише читаємо статус для індикатора
    private val analyzer = VibrationAnalyzer()
    private val simulator = WearSimulator()

    private val _state = MutableStateFlow(MonitorState(sensorAvailable = sensorReader.isAvailable))
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val _events = Channel<MonitorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var sensorJob: Job? = null

    init {
        // ЛР3: статус хмари → State, щоб на Моніторі був індикатор синхронізації
        viewModelScope.launch {
            cloudRepository.state.collect { sync ->
                _state.update { it.copy(cloudStatus = sync.status, cloudOk = sync.indicatorOk) }
            }
        }
    }

    // ЄДИНИЙ вхід у ViewModel — всі дії приходять сюди
    fun onAction(action: MonitorAction) {
        when (action) {
            MonitorAction.Start -> startSensor()
            MonitorAction.Stop -> stopSensor()
            is MonitorAction.SimulationToggled ->
                _state.update { it.copy(simulationEnabled = action.enabled) }
            is MonitorAction.WearLevelChanged ->
                _state.update { it.copy(simulatedWear = action.level.coerceIn(0f, 1f)) }
            MonitorAction.ResetWearClick -> resetWear()
        }
    }

    private fun startSensor() {
        if (sensorJob != null || !sensorReader.isAvailable) return
        _state.update { it.copy(isRunning = true) }
        sensorJob = viewModelScope.launch {
            sensorReader.readings().collect(::onSample)
        }
    }

    private fun stopSensor() {
        sensorJob?.cancel()   // скасування Flow → unregisterListener()
        sensorJob = null
        _state.update { it.copy(isRunning = false) }
    }

    private fun onSample(sample: AccelSample) {
        val current = _state.value
        val offset = if (current.simulationEnabled) {
            simulator.offset(current.simulatedWear, sample.timestampNs)
        } else {
            floatArrayOf(0f, 0f, 0f)
        }
        val snapshot = analyzer.process(
            sample.x + offset[0], sample.y + offset[1], sample.z + offset[2], sample.timestampNs
        )
        _state.update { it.copy(snapshot = snapshot) }
        repository.publish(snapshot.toReading())   // дані для смарт-хаба (ЛР2)
    }

    private fun resetWear() {
        analyzer.resetWear()
        _state.update { s -> s.copy(snapshot = s.snapshot?.copy(wearPercent = 0f)) }
        viewModelScope.launch { _events.send(MonitorEvent.ShowMessage("Знос скинуто")) }
    }
}

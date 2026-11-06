package com.lab.vibrationanalyzer.control

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lab.vibrationanalyzer.SmartVibrationApp
import com.lab.vibrationanalyzer.analysis.Decision
import com.lab.vibrationanalyzer.analysis.DecisionEngine
import com.lab.vibrationanalyzer.analysis.DecisionInput
import com.lab.vibrationanalyzer.helpers.actuator.SmartActuator
import com.lab.vibrationanalyzer.helpers.cloud.ChannelMode
import com.lab.vibrationanalyzer.repository.ActuatorReport
import com.lab.vibrationanalyzer.repository.ControlCommand
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ЛР4. «Мозок» екрана «Керування».
 *
 * Шлях даних (локальне автоматичне керування):
 *   SensorReader (SensorEventListener, ЛР1) → MonitorViewModel → VibrationRepository.latest →
 *   ЦЯ ViewModel → DecisionEngine.decide() → SmartActuator.triggerAlarm() / toggleFlashlight()
 *
 * Шлях команди (дистанційне керування, Cloud-to-Device):
 *   консоль Firebase → потік подій (SSE) або Polling → ControlRepository.state →
 *   ЦЯ ViewModel → DecisionEngine → SmartActuator → ControlRepository.report() → Firebase (actuators)
 */
class ControlViewModel(app: Application) : AndroidViewModel(app) {

    private val smartApp = app as SmartVibrationApp
    private val vibrationRepository = smartApp.repository
    private val controlRepository = smartApp.controlRepository

    // Помічник, що працює з Android API (VibratorManager, CameraManager)
    private val actuator = SmartActuator(app)
    private val engine = DecisionEngine()

    private val _state = MutableStateFlow(
        ControlState(
            hasFlash = actuator.hasFlash,
            hasVibrator = actuator.hasVibrator,
            threshold = engine.threshold,
            releaseThreshold = engine.releaseThreshold,
            channelEnabled = controlRepository.isConfigured,
            availableModes = controlRepository.availableModes,
            remote = controlRepository.state.value
        )
    )
    val state: StateFlow<ControlState> = _state.asStateFlow()

    private val _events = Channel<ControlEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var channelJob: Job? = null
    private var localThreshold = engine.threshold
    private var lastDecision: Decision? = null
    private var lastTorchCommand: Boolean? = null

    init {
        // 1. Фактичний стан ліхтарика (TorchCallback) → State
        viewModelScope.launch {
            actuator.torchOn.collect { on -> _state.update { it.copy(torchOn = on) } }
        }

        // 2. Стан каналу команд → State; нова команда з хмари → нове рішення
        viewModelScope.launch {
            controlRepository.state.collect { remote ->
                _state.update { it.copy(remote = remote) }
            }
        }
        viewModelScope.launch {
            controlRepository.state
                .distinctUntilChangedBy { it.command }
                .collect { remote -> onCloudCommand(remote.command) }
        }

        // 3. Кожен новий відлік сенсора → DecisionEngine (IF rms ≥ threshold THEN …)
        viewModelScope.launch {
            vibrationRepository.latest.filterNotNull().collect { reading ->
                _state.update { it.copy(rms = reading.rms, vibrationState = reading.state) }
                evaluate()
            }
        }

        // 4. ЛР6: прогноз моделі. Змінився прапорець «аномалія» → нове рішення
        viewModelScope.launch {
            vibrationRepository.aiInsight.collect { insight ->
                val anomaly = insight?.anomaly == true
                val changed = anomaly != _state.value.aiAnomaly
                _state.update { it.copy(aiAnomaly = anomaly, aiClass = insight?.headline) }
                if (changed) {
                    Log.d(TAG, "ШІ: аномалія=$anomaly (${insight?.headline ?: "ШІ вимкнено"})")
                    evaluate()
                }
            }
        }

        if (controlRepository.isConfigured) startChannel(_state.value.remote.mode)
    }

    // ЄДИНИЙ вхід у ViewModel — всі дії приходять сюди
    fun onAction(action: ControlAction) {
        when (action) {
            is ControlAction.AutoToggled -> {
                _state.update { it.copy(autoEnabled = action.enabled) }
                evaluate()
            }
            is ControlAction.ThresholdChanged -> {
                localThreshold = action.value
                applyThreshold()
                evaluate()
            }
            is ControlAction.ManualTorchChanged -> {
                _state.update { it.copy(manualTorch = action.mode) }
                evaluate()
            }
            is ControlAction.ManualAlarmChanged -> {
                _state.update { it.copy(manualAlarm = action.mode) }
                evaluate()
            }
            ControlAction.TestVibrationClick -> {
                actuator.triggerAlarm()
                _state.update { it.copy(vibrationCount = it.vibrationCount + 1) }
                if (!actuator.hasVibrator) send("Вібромотора немає: сигнал показано лише на екрані")
            }
            is ControlAction.ChannelToggled -> {
                if (action.enabled) startChannel(_state.value.remote.mode) else stopChannel()
                applyThreshold()
                evaluate()   // вимкнули канал → команди хмари більше не діють
            }
            is ControlAction.ChannelModeChanged -> if (_state.value.channelEnabled) {
                startChannel(action.mode)
            }
        }
    }

    private fun startChannel(mode: ChannelMode) {
        if (!controlRepository.isConfigured) return
        channelJob?.cancel()   // попередній канал (інший режим) закриваємо
        _state.update { it.copy(channelEnabled = true) }
        channelJob = viewModelScope.launch { controlRepository.listen(mode) }
    }

    private fun stopChannel() {
        channelJob?.cancel()   // скасування → awaitClose → EventSource.cancel()
        channelJob = null
        _state.update { it.copy(channelEnabled = false) }
    }

    private fun onCloudCommand(command: ControlCommand) {
        applyThreshold()
        evaluate()
        Log.d(TAG, "команда з хмари застосована: $command")
    }

    /** Поріг з хмари (control/threshold) має пріоритет над повзунком на екрані. */
    private fun applyThreshold() {
        val cloud = _state.value.remote.command.threshold?.takeIf { _state.value.channelEnabled }
        engine.threshold = cloud ?: localThreshold
        _state.update {
            it.copy(
                threshold = engine.threshold,
                releaseThreshold = engine.releaseThreshold,
                thresholdFromCloud = cloud != null
            )
        }
    }

    /** Прийняти рішення і виконати його на актуаторах. */
    private fun evaluate() {
        val s = _state.value
        val command = if (s.channelEnabled) s.remote.command else ControlCommand()
        val decision = engine.decide(
            DecisionInput(
                rms = s.rms,
                nowMs = System.currentTimeMillis(),
                autoEnabled = s.autoEnabled && command.autoEnabled,
                manualTorch = s.manualTorch,
                manualAlarm = s.manualAlarm,
                cloudTorch = command.ledStatus,
                cloudAlarm = command.alarm,
                aiAnomaly = s.aiAnomaly   // ЛР6
            )
        )

        // Ліхтарик: команду шлемо лише при зміні, а не 50 разів на секунду
        if (decision.torchOn != lastTorchCommand) {
            lastTorchCommand = decision.torchOn
            actuator.toggleFlashlight(decision.torchOn)
        }
        if (decision.vibrateNow) {
            actuator.triggerAlarm()
            _state.update { it.copy(vibrationCount = it.vibrationCount + 1) }
        }
        if (decision.stopVibration) actuator.stopAlarm()

        val previous = lastDecision
        lastDecision = decision
        _state.update { it.copy(decision = decision) }
        // ЛР5: стан актуаторів → репозиторій, звідти SensorViewModel пише його в БД (actionTriggered)
        vibrationRepository.publishActuatorState(decision.torchOn || decision.alarmActive)

        val outputsChanged = previous == null ||
            previous.torchOn != decision.torchOn || previous.alarmActive != decision.alarmActive ||
            previous.torchSource != decision.torchSource || previous.alarmSource != decision.alarmSource
        if (outputsChanged) onOutputsChanged(previous, decision)
    }

    private fun onOutputsChanged(previous: Decision?, decision: Decision) {
        Log.d(TAG, decision.reason)
        controlRepository.addLog(
            "прожектор=${onOff(decision.torchOn)} (${decision.torchSource?.title ?: "—"}), " +
                "сирена=${onOff(decision.alarmActive)} (${decision.alarmSource?.title ?: "—"})"
        )
        if (previous != null && !previous.alarmActive && decision.alarmActive) {
            send("Тривога: ${decision.alarmSource?.title}. Увімкнено вібросирену")
        }
        // Зворотний зв'язок у хмару: що реально зроблено
        viewModelScope.launch {
            controlRepository.report(
                ActuatorReport(
                    torch = decision.torchOn,
                    torchSource = decision.torchSource?.name ?: "NONE",
                    torchHardware = actuator.hasFlash,
                    alarm = decision.alarmActive,
                    alarmSource = decision.alarmSource?.name ?: "NONE",
                    rms = Math.round(_state.value.rms * 100f) / 100f,
                    threshold = engine.threshold,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    private fun onOff(on: Boolean) = if (on) "УВІМК" else "вимк"

    private fun send(text: String) {
        viewModelScope.launch { _events.send(ControlEvent.ShowSnackbar(text)) }
    }

    override fun onCleared() {
        actuator.release()   // вимикаємо ліхтарик і вібрацію, знімаємо TorchCallback
        super.onCleared()
    }

    private companion object {
        const val TAG = "DECISION"
    }
}

package com.lab.vibrationanalyzer.control

import com.lab.vibrationanalyzer.analysis.Decision
import com.lab.vibrationanalyzer.analysis.ManualMode
import com.lab.vibrationanalyzer.analysis.VibrationState
import com.lab.vibrationanalyzer.helpers.cloud.ChannelMode
import com.lab.vibrationanalyzer.repository.RemoteControlState

// State — усе, що видно на екрані «Керування» (ЛР4). Тільки val, значення за замовчуванням.
data class ControlState(
    // Дані сенсора з ЛР1
    val rms: Float = 0f,
    val vibrationState: VibrationState? = null,
    // DecisionEngine
    val autoEnabled: Boolean = true,          // перемикач на екрані
    val threshold: Float = VibrationState.CRITICAL_THRESHOLD,
    val releaseThreshold: Float = VibrationState.CRITICAL_THRESHOLD * 0.8f,
    val thresholdFromCloud: Boolean = false,  // поріг задано в Firebase (control/threshold)
    val decision: Decision? = null,
    // Manual Control
    val manualTorch: ManualMode = ManualMode.AUTO,
    val manualAlarm: ManualMode = ManualMode.AUTO,
    // Актуатори (зворотний зв'язок від апаратури)
    val torchOn: Boolean = false,
    val hasFlash: Boolean = false,
    val hasVibrator: Boolean = false,
    val vibrationCount: Int = 0,
    // Cloud-to-Device
    val channelEnabled: Boolean = true,
    val availableModes: List<ChannelMode> = emptyList(),   // SDK — лише з google-services.json
    val remote: RemoteControlState = RemoteControlState()
)

// Action — дії користувача на екрані «Керування».
sealed interface ControlAction {
    data class AutoToggled(val enabled: Boolean) : ControlAction
    data class ThresholdChanged(val value: Float) : ControlAction
    data class ManualTorchChanged(val mode: ManualMode) : ControlAction
    data class ManualAlarmChanged(val mode: ManualMode) : ControlAction
    data object TestVibrationClick : ControlAction
    data class ChannelToggled(val enabled: Boolean) : ControlAction
    data class ChannelModeChanged(val mode: ChannelMode) : ControlAction
}

// Event — разові повідомлення. Показуються як Snackbar на будь-якій вкладці.
sealed interface ControlEvent {
    data class ShowSnackbar(val text: String) : ControlEvent
}

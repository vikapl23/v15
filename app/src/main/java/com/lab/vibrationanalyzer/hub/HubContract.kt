package com.lab.vibrationanalyzer.hub

import com.lab.vibrationanalyzer.helpers.ble.BleConnectionState
import com.lab.vibrationanalyzer.helpers.ble.BleDevice
import com.lab.vibrationanalyzer.helpers.ble.BleLogEntry
import com.lab.vibrationanalyzer.helpers.ble.HubStatus

// State — усе, що видно на екрані «Смарт-хаб».
data class HubState(
    val isScanning: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val connection: BleConnectionState = BleConnectionState.DISCONNECTED,
    val connectedDevice: BleDevice? = null,
    val hubStatus: HubStatus? = null,
    val autoTelemetry: Boolean = true,
    val autoProtect: Boolean = true,
    val interference: Boolean = false,
    val packetsDelivered: Int = 0,
    val packetsFailed: Int = 0,
    val commandText: String = "",
    val terminal: List<BleLogEntry> = emptyList()
) {
    /** Команди можна надсилати лише при встановленому з'єднанні. */
    val canSend: Boolean get() = connection.isConnected
}

// Action — усі дії користувача на цьому екрані.
sealed interface HubAction {
    data object ScanClick : HubAction
    data class PermissionsResult(val granted: Boolean) : HubAction
    data class ConnectClick(val device: BleDevice) : HubAction
    data object DisconnectClick : HubAction
    data object PingClick : HubAction
    data class RelayToggled(val on: Boolean) : HubAction
    data class AutoTelemetryToggled(val enabled: Boolean) : HubAction
    data class AutoProtectToggled(val enabled: Boolean) : HubAction
    data class InterferenceToggled(val enabled: Boolean) : HubAction
    data class CommandTextChanged(val text: String) : HubAction
    data object SendCommandClick : HubAction
    data object ClearTerminalClick : HubAction
}

// Event — разові дії: запит дозволів, Toast.
sealed interface HubEvent {
    data object RequestBlePermissions : HubEvent
    data class ShowMessage(val text: String) : HubEvent
}

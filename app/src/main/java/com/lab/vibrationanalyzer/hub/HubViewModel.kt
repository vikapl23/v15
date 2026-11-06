package com.lab.vibrationanalyzer.hub

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lab.vibrationanalyzer.SmartVibrationApp
import com.lab.vibrationanalyzer.analysis.VibrationState
import com.lab.vibrationanalyzer.helpers.ble.BleConnectionState
import com.lab.vibrationanalyzer.helpers.ble.BleDevice
import com.lab.vibrationanalyzer.helpers.ble.BleLogEntry
import com.lab.vibrationanalyzer.helpers.ble.BleLogKind
import com.lab.vibrationanalyzer.helpers.ble.BleLogcat
import com.lab.vibrationanalyzer.helpers.ble.IBleScanner
import com.lab.vibrationanalyzer.helpers.ble.MockBleConnector
import com.lab.vibrationanalyzer.helpers.ble.MockBleScanner
import com.lab.vibrationanalyzer.helpers.ble.VibroHubProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * «Мозок» екрана Смарт-хаб: сканування, підключення, команди, телеметрія.
 *
 * Bluetooth-логіка живе в помічниках helpers/ble (IBleScanner, IBleConnector).
 * Щоб перейти з емуляції на реальний пристрій, достатньо
 * підставити інші реалізації цих інтерфейсів — екран і State не змінюються.
 */
class HubViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as SmartVibrationApp).repository
    private val scanner: IBleScanner = MockBleScanner()
    private val connector = MockBleConnector(scope = viewModelScope)

    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state.asStateFlow()

    private val _events = Channel<HubEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var scanJob: Job? = null

    init {
        // Стан з'єднання з помічника → State
        viewModelScope.launch {
            connector.connectionState.collect { connection ->
                _state.update {
                    if (connection == BleConnectionState.DISCONNECTED) {
                        it.copy(connection = connection, connectedDevice = null, hubStatus = null)
                    } else {
                        it.copy(connection = connection)
                    }
                }
            }
        }
        // Журнал обміну → Logcat + термінал на екрані
        viewModelScope.launch { connector.logs.collect(::appendLog) }
        // Notify від хаба → стан хаба
        viewModelScope.launch {
            connector.notifications.collect { message ->
                VibroHubProfile.parseStatus(message)?.let { status ->
                    _state.update { it.copy(hubStatus = status) }
                }
            }
        }
        // Раз на секунду: телеметрія на хаб і автозахист
        viewModelScope.launch {
            while (isActive) {
                delay(TELEMETRY_PERIOD_MS)
                telemetryTick()
            }
        }
    }

    // ЄДИНИЙ вхід у ViewModel
    fun onAction(action: HubAction) {
        when (action) {
            HubAction.ScanClick ->
                if (!_state.value.isScanning) sendEvent(HubEvent.RequestBlePermissions)
            is HubAction.PermissionsResult -> {
                if (!action.granted) {
                    appendLog(BleLogEntry(BleLogKind.ERR, "Дозволи BLUETOOTH_SCAN/CONNECT не надано — працює лише емуляція"))
                }
                startScan()
            }
            is HubAction.ConnectClick -> connect(action.device)
            HubAction.DisconnectClick -> connector.disconnect()
            HubAction.PingClick -> send(VibroHubProfile.CMD_PING)
            is HubAction.RelayToggled ->
                send(if (action.on) VibroHubProfile.CMD_RELAY_ON else VibroHubProfile.CMD_RELAY_OFF)
            is HubAction.AutoTelemetryToggled ->
                _state.update { it.copy(autoTelemetry = action.enabled) }
            is HubAction.AutoProtectToggled ->
                _state.update { it.copy(autoProtect = action.enabled) }
            is HubAction.InterferenceToggled -> setInterference(action.enabled)
            is HubAction.CommandTextChanged ->
                _state.update { it.copy(commandText = action.text) }
            HubAction.SendCommandClick -> {
                val command = _state.value.commandText.trim().uppercase()
                if (command.isNotEmpty()) {
                    send(command)
                    _state.update { it.copy(commandText = "") }
                }
            }
            HubAction.ClearTerminalClick -> _state.update { it.copy(terminal = emptyList()) }
        }
    }

    private fun startScan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _state.update { it.copy(isScanning = true, devices = emptyList()) }
            appendLog(BleLogEntry(BleLogKind.SYS, "startScan(): пошук BLE-пристроїв…"))
            try {
                scanner.scan().collect { device ->
                    appendLog(
                        BleLogEntry(BleLogKind.SYS, "onScanResult: ${device.name} ${device.address} RSSI ${device.rssi} дБм")
                    )
                    _state.update { s ->
                        s.copy(devices = s.devices.filterNot { it.address == device.address } + device)
                    }
                }
                appendLog(BleLogEntry(BleLogKind.SYS, "stopScan(): знайдено пристроїв — ${_state.value.devices.size}"))
            } finally {
                _state.update { it.copy(isScanning = false) }
            }
        }
    }

    private fun connect(device: BleDevice) {
        if (_state.value.connection != BleConnectionState.DISCONNECTED) return
        scanJob?.cancel()   // перед підключенням сканування зупиняють
        _state.update { it.copy(connectedDevice = device) }
        viewModelScope.launch {
            connector.connect(device)
                .onSuccess { sendEvent(HubEvent.ShowMessage("Підключено до ${device.name}")) }
                .onFailure { e ->
                    _state.update { it.copy(connectedDevice = null) }
                    sendEvent(HubEvent.ShowMessage(e.message ?: "Помилка підключення"))
                }
        }
    }

    private fun send(data: String) {
        viewModelScope.launch { sendAndCount(data) }
    }

    private suspend fun sendAndCount(data: String): Result<Unit> =
        connector.sendData(data)
            .onSuccess { _state.update { it.copy(packetsDelivered = it.packetsDelivered + 1) } }
            .onFailure { _state.update { it.copy(packetsFailed = it.packetsFailed + 1) } }

    /** Відправка останнього відліку з репозиторію; при критичній вібрації — вимкнення реле. */
    private suspend fun telemetryTick() {
        val s = _state.value
        if (s.connection != BleConnectionState.CONNECTED) return
        val reading = repository.latest.value ?: return

        if (s.autoProtect && reading.state == VibrationState.CRITICAL && s.hubStatus?.relayOn == true) {
            sendAndCount(VibroHubProfile.CMD_RELAY_OFF)
                .onSuccess { sendEvent(HubEvent.ShowMessage("Критична вібрація: хаб вимкнув двигун")) }
            return
        }
        if (s.autoTelemetry) {
            sendAndCount(
                VibroHubProfile.telemetry(reading.rms, reading.peak, reading.state.name, reading.wearPercent)
            )
        }
    }

    private fun setInterference(enabled: Boolean) {
        connector.interference = enabled
        _state.update { it.copy(interference = enabled) }
        appendLog(
            BleLogEntry(
                BleLogKind.SYS,
                if (enabled) "Перешкоди УВІМКНЕНО: втрата і пошкодження пакетів, обриви зв'язку"
                else "Перешкоди вимкнено"
            )
        )
    }

    private fun appendLog(entry: BleLogEntry) {
        BleLogcat.write(entry)
        _state.update { it.copy(terminal = (it.terminal + entry).takeLast(MAX_TERMINAL_LINES)) }
    }

    private fun sendEvent(event: HubEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    override fun onCleared() {
        connector.disconnect()
        super.onCleared()
    }

    private companion object {
        const val TELEMETRY_PERIOD_MS = 1_000L
        const val MAX_TERMINAL_LINES = 200
    }
}

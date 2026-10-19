package com.lab.vibrationanalyzer.hub

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lab.vibrationanalyzer.helpers.ble.BleConnectionState
import com.lab.vibrationanalyzer.helpers.ble.BleDevice
import com.lab.vibrationanalyzer.helpers.ble.BleLogEntry
import com.lab.vibrationanalyzer.helpers.ble.BleLogKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Дозволи Bluetooth, які на Android 12+ запитуються під час роботи. */
private val BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT
)

private val ColorConnected = Color(0xFF2E7D32)
private val ColorPending = Color(0xFFF9A825)
private val ColorDisconnected = Color(0xFF9E9E9E)
private val ColorTerminal = Color(0xFF101418)

@Composable
fun HubScreen(vm: HubViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Результат системного діалогу дозволів повертаємо у ViewModel як Action
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        vm.onAction(HubAction.PermissionsResult(granted = result.values.all { it }))
    }

    // Слухаємо разові Event
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                HubEvent.RequestBlePermissions -> permissionLauncher.launch(BLE_PERMISSIONS)
                is HubEvent.ShowMessage ->
                    Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    HubContent(state = state, onAction = vm::onAction, modifier = modifier)
}

// Чиста функція: State на вході, Action на виході.
@Composable
fun HubContent(
    state: HubState,
    onAction: (HubAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Смарт-хаб (BLE)",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        ConnectionCard(state, onAction)
        ScanCard(state, onAction)
        if (state.connectedDevice != null && state.connection != BleConnectionState.CONNECTING) {
            ControlCard(state, onAction)
        }
        TerminalCard(state.terminal, onClear = { onAction(HubAction.ClearTerminalClick) })
    }
}

private fun BleConnectionState.color(): Color = when (this) {
    BleConnectionState.CONNECTED, BleConnectionState.DATA_TRANSFER -> ColorConnected
    BleConnectionState.CONNECTING, BleConnectionState.RECONNECTING -> ColorPending
    BleConnectionState.DISCONNECTED -> ColorDisconnected
}

/** Індикатор статусу з'єднання: Connected / Disconnected. */
@Composable
private fun ConnectionCard(state: HubState, onAction: (HubAction) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(state.connection.color())
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    state.connection.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = state.connection.color()
                )
            }
            Spacer(Modifier.height(4.dp))
            val device = state.connectedDevice
            Text(
                if (device != null) "${device.name} · ${device.address}" else "Пристрій не підключено",
                style = MaterialTheme.typography.bodyMedium
            )
            if (device != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onAction(HubAction.DisconnectClick) }) { Text("Відключитися") }
            }
        }
    }
}

/** Кнопка «Пошук пристроїв», ProgressBar під час сканування і список знайдених. */
@Composable
private fun ScanCard(state: HubState, onAction: (HubAction) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Button(
                onClick = { onAction(HubAction.ScanClick) },
                enabled = !state.isScanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isScanning) "Сканування…" else "Пошук пристроїв")
            }
            if (state.isScanning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Text("Знайдені пристрої: ${state.devices.size}", fontWeight = FontWeight.SemiBold)
            if (state.devices.isEmpty() && !state.isScanning) {
                Text("Натисніть «Пошук пристроїв»", style = MaterialTheme.typography.bodySmall)
            }
            state.devices.forEach { device ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                DeviceRow(
                    device = device,
                    canConnect = state.connection == BleConnectionState.DISCONNECTED,
                    onConnect = { onAction(HubAction.ConnectClick(device)) }
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: BleDevice, canConnect: Boolean, onConnect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(device.name, fontWeight = FontWeight.SemiBold)
            Text(
                "${device.address} · RSSI ${device.rssi} дБм",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                if (device.isSupported) "Сервіс VibroHub ✓" else "Сервіс не підтримується",
                style = MaterialTheme.typography.bodySmall,
                color = if (device.isSupported) ColorConnected else ColorDisconnected
            )
        }
        Button(onClick = onConnect, enabled = canConnect) { Text("Підключити") }
    }
}

/** Керування хабом: реле, PING, телеметрія, перешкоди, довільна команда. */
@Composable
private fun ControlCard(state: HubState, onAction: (HubAction) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Керування хабом", fontWeight = FontWeight.SemiBold)
            val status = state.hubStatus
            Text(
                if (status != null) {
                    "Реле: ${if (status.relayOn) "УВІМК" else "ВИМК"} · Батарея: ${status.batteryPercent} % · Прийнято: ${status.packetsReceived}"
                } else {
                    "Стан хаба невідомий"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            SwitchRow(
                text = "Реле двигуна",
                checked = status?.relayOn == true,
                enabled = state.canSend,
                onChange = { onAction(HubAction.RelayToggled(it)) }
            )
            SwitchRow(
                text = "Телеметрія щосекунди",
                checked = state.autoTelemetry,
                onChange = { onAction(HubAction.AutoTelemetryToggled(it)) }
            )
            SwitchRow(
                text = "Автозахист: вимкнути двигун при критичній вібрації",
                checked = state.autoProtect,
                onChange = { onAction(HubAction.AutoProtectToggled(it)) }
            )
            SwitchRow(
                text = "Імітація перешкод",
                checked = state.interference,
                onChange = { onAction(HubAction.InterferenceToggled(it)) }
            )
            Text(
                "Пакетів доставлено: ${state.packetsDelivered} · не доставлено: ${state.packetsFailed}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.commandText,
                    onValueChange = { onAction(HubAction.CommandTextChanged(it)) },
                    label = { Text("Команда") },
                    placeholder = { Text("STATUS?") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onAction(HubAction.SendCommandClick) },
                    enabled = state.canSend && state.commandText.isNotBlank()
                ) { Text("Надіслати") }
            }
            OutlinedButton(
                onClick = { onAction(HubAction.PingClick) },
                enabled = state.canSend
            ) { Text("PING") }
        }
    }
}

@Composable
private fun SwitchRow(
    text: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Термінал: журнал обміну з пристроєм (те саме йде в Logcat з тегом BLE_MOCK). */
@Composable
private fun TerminalCard(lines: List<BleLogEntry>, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    // Автопрокрутка до останнього рядка
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = ColorTerminal),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Термінал обміну",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClear) { Text("Очистити") }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                items(lines) { entry ->
                    Text(
                        "${timeFormat.format(Date(entry.timeMs))} ${entry.kind.symbol()} ${entry.message}",
                        color = entry.kind.color(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

private fun BleLogKind.symbol(): String = when (this) {
    BleLogKind.TX -> "→"
    BleLogKind.RX -> "←"
    BleLogKind.SYS -> "•"
    BleLogKind.ERR -> "!"
}

private fun BleLogKind.color(): Color = when (this) {
    BleLogKind.TX -> Color(0xFF4FC3F7)
    BleLogKind.RX -> Color(0xFF81C784)
    BleLogKind.SYS -> Color(0xFFB0BEC5)
    BleLogKind.ERR -> Color(0xFFE57373)
}

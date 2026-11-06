package com.lab.vibrationanalyzer.control

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lab.vibrationanalyzer.analysis.CommandSource
import com.lab.vibrationanalyzer.analysis.DecisionEngine
import com.lab.vibrationanalyzer.analysis.ManualMode
import com.lab.vibrationanalyzer.helpers.cloud.ChannelMode
import com.lab.vibrationanalyzer.repository.ChannelStatus
import com.lab.vibrationanalyzer.repository.ControlLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ColorOff = Color(0xFF9E9E9E)
private val ColorAuto = Color(0xFFC62828)     // увімкнено автоматикою (поріг) — червоний
private val ColorCloud = Color(0xFF1565C0)    // увімкнено командою з хмари — синій
private val ColorManual = Color(0xFFF9A825)   // увімкнено вручну — жовтий
private val ColorAi = Color(0xFF6A1B9A)       // ЛР6: увімкнено моделлю ШІ — фіолетовий
private val ColorOk = Color(0xFF2E7D32)

/** Колір віртуальної лампи залежить від того, ХТО її ввімкнув. */
private fun lampColor(on: Boolean, source: CommandSource?): Color = when {
    !on -> ColorOff
    source == CommandSource.CLOUD -> ColorCloud
    source == CommandSource.MANUAL -> ColorManual
    source == CommandSource.AI -> ColorAi
    else -> ColorAuto
}

/** ЛР4. Екран «Керування». Event (Snackbar) слухає MainActivity. */
@Composable
fun ControlScreen(vm: ControlViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    ControlContent(state = state, onAction = vm::onAction, modifier = modifier)
}

// Чиста функція: State на вході, Action на виході. Жодної логіки — лише малювання.
@Composable
fun ControlContent(
    state: ControlState,
    onAction: (ControlAction) -> Unit,
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
            "Керування актуаторами",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        ActuatorsCard(state)
        DecisionCard(state, onAction)
        ManualControlCard(state, onAction)
        CloudControlCard(state, onAction)
        LogCard(state.remote.log)
    }
}

/** Віртуальні виконавчі пристрої: лампа-прожектор і сирена. */
@Composable
private fun ActuatorsCard(state: ControlState) {
    val decision = state.decision
    val torchColor = lampColor(state.torchOn, decision?.torchSource)
    val alarmOn = decision?.alarmActive == true
    val alarmColor = lampColor(alarmOn, decision?.alarmSource)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Виконавчі пристрої", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    VirtualLamp(torchColor, state.torchOn, Modifier.size(96.dp))
                    Text("Прожектор", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.torchOn) "УВІМК · ${decision?.torchSource?.title ?: ""}" else "вимкнено",
                        style = MaterialTheme.typography.bodySmall, color = torchColor
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    VirtualLamp(alarmColor, alarmOn, Modifier.size(96.dp), label = "🔊")
                    Text("Сирена (вібро)", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (alarmOn) "ТРИВОГА · ${decision?.alarmSource?.title ?: ""}" else "вимкнено",
                        style = MaterialTheme.typography.bodySmall, color = alarmColor
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Спалах (FEATURE_CAMERA_FLASH): " +
                    if (state.hasFlash) "є — керуємо через CameraManager" else "немає — віртуальний режим",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Вібромотор: ${if (state.hasVibrator) "є" else "немає"} · вібросигналів: ${state.vibrationCount}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Колір лампи: червоний — поріг, синій — хмара, жовтий — вручну",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** Віртуальна лампа на Canvas: коло з «світінням», коли увімкнено. */
@Composable
private fun VirtualLamp(color: Color, on: Boolean, modifier: Modifier = Modifier, label: String = "💡") {
    val outline = MaterialTheme.colorScheme.outline
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            if (on) {
                drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = 0.6f), Color.Transparent), center, r),
                    radius = r
                )
            }
            drawCircle(color, radius = r * 0.55f)
            drawCircle(outline, radius = r * 0.55f, style = Stroke(2.dp.toPx()))
        }
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}

/** DecisionEngine: правило, поточне значення і поріг. */
@Composable
private fun DecisionCard(state: ControlState, onAction: (ControlAction) -> Unit) {
    val decision = state.decision
    val exceeded = decision?.thresholdExceeded == true
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (exceeded) ColorAuto.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("DecisionEngine (локальне автоматичне керування)", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "IF RMS ≥ %.2f м/с² THEN сирена + прожектор".format(state.threshold),
                fontFamily = FontFamily.Monospace
            )
            Text(
                "вимкнення при RMS < %.2f м/с² (гістерезис)".format(state.releaseThreshold),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            // ЛР6: друге правило — модель TensorFlow Lite
            Text(
                "IF ШІ == «Спроба злому» THEN сирена + прожектор",
                fontFamily = FontFamily.Monospace,
                color = if (state.aiAnomaly) ColorAi else MaterialTheme.colorScheme.onSurface
            )
            Text(
                "ШІ зараз: ${state.aiClass ?: "вимкнено / накопичує дані"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.aiAnomaly) ColorAi else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Зараз: RMS = %.2f м/с² · стан «%s»".format(state.rms, state.vibrationState?.title ?: "—"),
                fontWeight = FontWeight.SemiBold,
                color = if (exceeded) ColorAuto else MaterialTheme.colorScheme.onSurface
            )
            decision?.let { Text(it.reason, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Автоматичне керування", Modifier.weight(1f))
                Switch(checked = state.autoEnabled, onCheckedChange = { onAction(ControlAction.AutoToggled(it)) })
            }
            if (!state.remote.command.autoEnabled && state.channelEnabled) {
                Text(
                    "Автоматику вимкнено з хмари (control/auto_enabled = false)",
                    color = ColorCloud, style = MaterialTheme.typography.bodySmall
                )
            }
            Text("Поріг (Threshold): %.2f м/с²".format(state.threshold))
            Slider(
                value = state.threshold,
                onValueChange = { onAction(ControlAction.ThresholdChanged((it * 10).toInt() / 10f)) },
                valueRange = DecisionEngine.MIN_THRESHOLD..3f,
                enabled = !state.thresholdFromCloud
            )
            if (state.thresholdFromCloud) {
                Text(
                    "Поріг задано з хмари (control/threshold)",
                    color = ColorCloud, style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** Manual Control: примусове ввімкнення/вимкнення актуаторів. */
@Composable
private fun ManualControlCard(state: ControlState, onAction: (ControlAction) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Manual Control (ручне керування)", fontWeight = FontWeight.SemiBold)
            Text(
                "«Увімк»/«Вимк» мають найвищий пріоритет. «Авто» віддає керування хмарі й порогу.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            ModeRow("Прожектор", state.manualTorch) { onAction(ControlAction.ManualTorchChanged(it)) }
            ModeRow("Сирена", state.manualAlarm) { onAction(ControlAction.ManualAlarmChanged(it)) }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { onAction(ControlAction.TestVibrationClick) }) {
                Text("Тест вібросигналу")
            }
        }
    }
}

@Composable
private fun ModeRow(title: String, selected: ManualMode, onSelect: (ManualMode) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ManualMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                    label = { Text(mode.title) }
                )
            }
        }
    }
}

/** Дистанційне керування Cloud-to-Device. */
@Composable
private fun CloudControlCard(state: ControlState, onAction: (ControlAction) -> Unit) {
    val remote = state.remote
    val statusColor = when (remote.status) {
        ChannelStatus.CONNECTED -> ColorOk
        ChannelStatus.CONNECTING -> ColorManual
        ChannelStatus.RECONNECTING -> ColorAuto
        ChannelStatus.STOPPED, ChannelStatus.NOT_CONFIGURED -> ColorOff
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Дистанційне керування (Cloud-to-Device)", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                if (remote.status != ChannelStatus.NOT_CONFIGURED) {
                    Switch(checked = state.channelEnabled, onCheckedChange = { onAction(ControlAction.ChannelToggled(it)) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(remote.status.title, color = statusColor, fontWeight = FontWeight.SemiBold)
            }
            if (remote.status == ChannelStatus.NOT_CONFIGURED) {
                Text(
                    "Вкажіть адресу Firebase Realtime Database у local.properties (cloud.url), як у ЛР3, " +
                        "або покладіть google-services.json у папку app. Тестовий REST API не вміє надсилати команди.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                CloudDetails(state, onAction)
            }
        }
    }
}

@Composable
private fun CloudDetails(state: ControlState, onAction: (ControlAction) -> Unit) {
    val remote = state.remote
    Column {
        remote.lastError?.let {
            Text("Помилка: $it", color = ColorAuto, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.availableModes.forEach { mode ->
                FilterChip(
                    selected = mode == remote.mode,
                    onClick = { onAction(ControlAction.ChannelModeChanged(mode)) },
                    label = { Text(mode.short) },
                    enabled = state.channelEnabled
                )
            }
        }
        Text(remote.mode.title, style = MaterialTheme.typography.bodySmall)
        if (ChannelMode.FIREBASE_SDK !in state.availableModes) {
            Text(
                "Режим SDK (ValueEventListener) з'явиться, коли покласти google-services.json у папку app.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(remote.endpoint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))

        val c = remote.command
        Text("Гілка control у Firebase:", fontWeight = FontWeight.SemiBold)
        Text(
            "led_status = ${c.ledStatus}\nalarm = ${c.alarm}\nauto_enabled = ${c.autoEnabled}\n" +
                "threshold = ${c.threshold ?: "—"}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = if (c.ledStatus || c.alarm) ColorCloud else MaterialTheme.colorScheme.onSurface
        )
        Text(
            "Остання команда: " + (remote.lastCommandAtMs?.let(::formatTime) ?: "—"),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Counter("Команд", remote.commandsReceived)
            Counter("HTTP-запитів", remote.httpRequests)
            Counter("Відповідей", remote.feedbackSent)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Зворотний зв'язок: ${remote.feedbackEndpoint}" + when (remote.lastFeedbackOk) {
                true -> " ✓"
                false -> " ✗"
                null -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun Counter(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun LogCard(log: List<ControlLogEntry>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Журнал керування", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (log.isEmpty()) Text("Поки порожньо", style = MaterialTheme.typography.bodySmall)
            log.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "${formatTime(e.timeMs)}  ${e.text}",
                    color = if (e.isError) ColorAuto else MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

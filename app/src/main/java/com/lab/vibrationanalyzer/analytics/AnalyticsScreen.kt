package com.lab.vibrationanalyzer.analytics

import android.widget.Toast
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lab.vibrationanalyzer.analysis.ChartPoint
import com.lab.vibrationanalyzer.analysis.RecordingMode
import com.lab.vibrationanalyzer.repository.local.AppDatabase
import com.lab.vibrationanalyzer.repository.local.SensorRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ColorRecording = Color(0xFF2E7D32)
private val ColorPaused = Color(0xFF9E9E9E)
private val ColorAlarm = Color(0xFFC62828)

/** ЛР5. Екран «Аналітика»: графік з БД, статистика, журнал спрацювань. */
@Composable
fun AnalyticsScreen(vm: SensorViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is SensorEvent.ShowMessage ->
                    Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    AnalyticsContent(state = state, onAction = vm::onAction, modifier = modifier)
}

// Чиста функція: State на вході, Action на виході. Жодної роботи з БД — лише малювання.
@Composable
fun AnalyticsContent(
    state: SensorState,
    onAction: (SensorAction) -> Unit,
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
            "Аналітика",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Історія з локальної БД Room — працює без інтернету",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ChartCard(state, onAction)
        RecordingCard(state, onAction)
        StatsCard(state)
        TriggeredCard(state.triggeredHistory)
    }
}

@Composable
private fun ChartCard(state: SensorState, onAction: (SensorAction) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Графік вібрації (LineChart)", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChartRange.entries.forEach { r ->
                    FilterChip(
                        selected = state.range == r,
                        onClick = { onAction(SensorAction.RangeChanged(r)) },
                        label = { Text(r.title) }
                    )
                }
            }
            if (state.points.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(260.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "У БД ще немає записів.\nВідкрийте «Монітор» і порухайте пристрій.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                VibrationLineChart(points = state.points, threshold = state.threshold)
            }
            val triggered = state.points.count { it.triggered }
            Text(
                "Точок на графіку: ${state.points.size}" +
                    (if (triggered > 0) " · з них під час тривоги: $triggered" else ""),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "X — час виміру, Y — RMS вібрації (м/с²). Червона лінія — поріг тривоги ЛР4.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecordingCard(state: SensorState, onAction: (SensorAction) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (state.recordingEnabled) ColorRecording else ColorPaused)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.recordingEnabled) "Запис у БД увімкнено" else "Запис на паузі",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = state.recordingEnabled,
                    onCheckedChange = { onAction(SensorAction.RecordingToggled(it)) }
                )
            }
            Text("Коли записувати:", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordingMode.entries.forEach { m ->
                    FilterChip(
                        selected = state.mode == m,
                        onClick = { onAction(SensorAction.ModeChanged(m)) },
                        label = { Text(m.title) }
                    )
                }
            }
            Text(
                when (state.mode) {
                    RecordingMode.ON_CHANGE ->
                        "Запис, коли RMS змінився щонайменше на 0,05 м/с² (не частіше 5 разів/с), " +
                            "а в спокої — раз на 5 с."
                    RecordingMode.TIMER -> "Запис рівно раз на 1 с, незалежно від значення."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // ЛР6: модель ШІ керує частотою запису
            InfoRow(
                "ШІ (TFLite)",
                state.aiClass ?: "вимкнено",
                valueColor = if (state.aiBoosted) ColorAlarm else null
            )
            InfoRow(
                "Інтервал запису",
                "${state.writeIntervalMs} мс" + if (state.aiBoosted) " ⚡ прискорено ШІ" else "",
                valueColor = if (state.aiBoosted) ColorAlarm else null
            )
            HorizontalDivider()
            InfoRow("Поточне значення сенсора", state.liveValue?.let { "%.3f м/с²".format(it) } ?: "—")
            InfoRow(
                "Актуатор (ЛР4)",
                if (state.actuatorActive) "УВІМКНЕНО" else "вимкнено",
                valueColor = if (state.actuatorActive) ColorAlarm else null
            )
            InfoRow("Записано за сеанс", state.savedThisSession.toString())
            InfoRow("Останній запис", state.lastSavedAtMs?.let(::formatTime) ?: "—")
            InfoRow("Усього рядків у таблиці", state.totalRecords.toString())
            Text(
                "Файл БД: ${AppDatabase.DATABASE_NAME}, таблиця ${SensorRecord.TABLE}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            OutlinedButton(onClick = { onAction(SensorAction.ClearClick) }) {
                Text("Очистити БД")
            }
        }
    }
}

@Composable
private fun StatsCard(state: SensorState) {
    val s = state.hourStats
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("За останню годину (SQL: COUNT, AVG, MAX)", style = MaterialTheme.typography.titleMedium)
            InfoRow("Записів", s.count.toString())
            InfoRow("Середній RMS", s.average?.let { "%.3f м/с²".format(it) } ?: "—")
            InfoRow("Максимальний RMS", s.maximum?.let { "%.3f м/с²".format(it) } ?: "—")
            InfoRow("Записів під час тривоги", (s.triggeredCount ?: 0).toString())
        }
    }
}

@Composable
private fun TriggeredCard(history: List<ChartPoint>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Історія спрацювань системи", style = MaterialTheme.typography.titleMedium)
            if (history.isEmpty()) {
                Text(
                    "Спрацювань ще не було. Щоб перевірити: «Монітор» → імітація зносу 80–100 %.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                history.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(ColorAlarm)
                        Spacer(Modifier.width(8.dp))
                        Text(formatDateTime(p.timestampMs), fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        Text("%.2f м/с²".format(p.value), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(12.dp).background(color, CircleShape))
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

private fun formatDateTime(ms: Long): String =
    SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(ms))

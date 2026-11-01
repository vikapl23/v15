package com.lab.vibrationanalyzer.cloud

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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lab.vibrationanalyzer.repository.CloudRecord
import com.lab.vibrationanalyzer.repository.SyncStatus
import com.lab.vibrationanalyzer.ui.CloudErrorColor
import com.lab.vibrationanalyzer.ui.CloudOkColor
import com.lab.vibrationanalyzer.ui.cloudColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** ЛР3. Екран «Хмара». Event (Snackbar) слухає MainActivity, щоб він був видний на будь-якій вкладці. */
@Composable
fun CloudScreen(vm: CloudViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    CloudContent(state = state, onAction = vm::onAction, modifier = modifier)
}

// Чиста функція: State на вході, Action на виході.
@Composable
fun CloudContent(
    state: CloudState,
    onAction: (CloudAction) -> Unit,
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
            "Хмарна синхронізація",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        StatusCard(state)
        if (state.usingMockApi) MockHintCard()
        StatsCard(state, onRetry = { onAction(CloudAction.RetryClick) })
        SettingsCard(state, onAction)
        state.lastJson?.let { JsonCard(it) }
        LogCard(state.sync.log)
    }
}

/** Великий індикатор: зелений — останній запит успішний, червоний — помилка/Pending. */
@Composable
private fun StatusCard(state: CloudState) {
    val sync = state.sync
    val color = cloudColor(sync.indicatorOk)
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (sync.status == SyncStatus.SENDING) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 3.dp)
                } else {
                    Box(Modifier.size(16.dp).background(color, CircleShape))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    sync.status.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(sync.backendName, color = Color.White)
                Text(
                    "Мережа: ${if (state.isOnline) "є" else "немає"} · пристрій ${state.deviceId}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun MockHintCard() {
    Card(Modifier.fillMaxWidth()) {
        Text(
            "Firebase не налаштовано (cloud.url у local.properties порожній), тому дані йдуть " +
                "на тестовий REST API JSONPlaceholder. Він приймає POST, але нічого не зберігає.",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun StatsCard(state: CloudState, onRetry: () -> Unit) {
    val sync = state.sync
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Статистика", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Counter("Надіслано", sync.sentCount, CloudOkColor)
                Counter("Помилок", sync.failedCount, CloudErrorColor)
                Counter("Pending", sync.pendingCount, MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Остання вдала відправка: " + (sync.lastSentAtMs?.let(::formatTime) ?: "—"),
                style = MaterialTheme.typography.bodySmall
            )
            sync.lastError?.let {
                Text("Остання помилка: $it", color = CloudErrorColor, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "POST " + sync.endpoint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            if (sync.pendingCount > 0) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRetry) { Text("Надіслати чергу зараз") }
            }
        }
    }
}

@Composable
private fun Counter(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SettingsCard(state: CloudState, onAction: (CloudAction) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Відправляти дані в хмару", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Switch(
                    checked = state.syncEnabled,
                    onCheckedChange = { onAction(CloudAction.SyncToggled(it)) }
                )
            }
            Text("Інтервал: кожні ${state.intervalSec} с")
            Slider(
                value = state.intervalSec.toFloat(),
                onValueChange = { onAction(CloudAction.IntervalChanged(it.toInt())) },
                valueRange = 1f..30f,
                steps = 28
            )
            Text("Δ: позачергова відправка при зміні на %.1f м/с²".format(state.delta))
            Slider(
                value = state.delta,
                onValueChange = { onAction(CloudAction.DeltaChanged((it * 10).toInt() / 10f)) },
                valueRange = 0.2f..5f
            )
            Text(
                "Акселерометр дає ~50 відліків/с, а в хмару йде один запис раз на інтервал " +
                    "або коли RMS чи X/Y/Z змінились на Δ (не частіше ніж раз на 1 с).",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun JsonCard(json: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Останній надісланий JSON", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(json, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LogCard(log: List<CloudRecord>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Журнал відправок", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (log.isEmpty()) Text("Поки порожньо", style = MaterialTheme.typography.bodySmall)
            log.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                LogRow(r)
            }
        }
    }
}

@Composable
private fun LogRow(r: CloudRecord) {
    val (mark, color) = when (r.result) {
        CloudRecord.Result.SENT -> "✓" to CloudOkColor
        CloudRecord.Result.PENDING -> "⏳" to MaterialTheme.colorScheme.onSurface
        CloudRecord.Result.FAILED -> "✗" to CloudErrorColor
    }
    val d = r.data
    Column {
        Text(
            "$mark ${formatTime(d.timestamp)} · ${r.reason} · RMS=%.2f".format(d.value),
            color = color,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "  x=%+.2f y=%+.2f z=%+.2f".format(d.x, d.y, d.z) + when (r.result) {
                CloudRecord.Result.SENT -> "  id=${r.remoteId}"
                CloudRecord.Result.PENDING -> "  Pending"
                CloudRecord.Result.FAILED -> "  ${r.error ?: ""}"
            },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

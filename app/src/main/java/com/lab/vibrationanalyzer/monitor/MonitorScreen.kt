package com.lab.vibrationanalyzer.monitor

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lab.vibrationanalyzer.analysis.VibrationSnapshot
import com.lab.vibrationanalyzer.analysis.VibrationState
import kotlin.math.cos
import kotlin.math.sin

private val ColorNormal = Color(0xFF2E7D32)
private val ColorElevated = Color(0xFFF9A825)
private val ColorCritical = Color(0xFFC62828)

/** Максимум шкали індикатора RMS, м/с². */
private const val GAUGE_MAX = 3f

private fun VibrationState.color(): Color = when (this) {
    VibrationState.NORMAL -> ColorNormal
    VibrationState.ELEVATED -> ColorElevated
    VibrationState.CRITICAL -> ColorCritical
}

@Composable
fun MonitorScreen(vm: MonitorViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Слухаємо разові Event
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is MonitorEvent.ShowMessage ->
                    Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    MonitorContent(state = state, onAction = vm::onAction, modifier = modifier)
}

// Чиста функція: State на вході, Action на виході. Жодної логіки — лише малювання.
@Composable
fun MonitorContent(
    state: MonitorState,
    onAction: (MonitorAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val snapshot = state.snapshot
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Smart Vibration Analyzer",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        when {
            !state.sensorAvailable -> Text(
                "Акселерометр недоступний на цьому пристрої",
                color = ColorCritical
            )
            snapshot == null -> Text("Очікування даних від сенсора…")
            else -> {
                StateCard(snapshot)
                AxesCard(snapshot)
                GaugeCard(snapshot)
                HistoryCard(snapshot)
                WearCard(snapshot, onReset = { onAction(MonitorAction.ResetWearClick) })
                SimulationCard(
                    enabled = state.simulationEnabled,
                    level = state.simulatedWear,
                    onEnabledChange = { onAction(MonitorAction.SimulationToggled(it)) },
                    onLevelChange = { onAction(MonitorAction.WearLevelChanged(it)) }
                )
            }
        }
    }
}

@Composable
private fun StateCard(s: VibrationSnapshot) {
    Card(
        colors = CardDefaults.cardColors(containerColor = s.state.color()),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Стан: ${s.state.title}",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(s.state.description, color = Color.White)
        }
    }
}

@Composable
private fun AxesCard(s: VibrationSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Дані акселерометра, м/с²", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AxisValue("X", s.x)
                AxisValue("Y", s.y)
                AxisValue("Z", s.z)
            }
            Spacer(Modifier.height(8.dp))
            Text("|a| = %.2f   RMS = %.2f   пік = %.2f".format(s.magnitude, s.rms, s.peak),
                fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun AxisValue(label: String, value: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            "%+.2f".format(value),
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * Графічні індикатори на Canvas:
 * ліворуч — стрілочний індикатор RMS з кольоровими зонами порогів,
 * праворуч — "рівень з бульбашкою", що показує нахил пристрою (осі X/Y).
 */
@Composable
private fun GaugeCard(s: VibrationSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Індикатори", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RmsGauge(s.rms, Modifier.size(150.dp))
                    Text("RMS вібрації")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TiltBubble(s.x, s.y, s.state.color(), Modifier.size(120.dp))
                    Text("Положення")
                }
            }
        }
    }
}

@Composable
private fun RmsGauge(rms: Float, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val startAngle = 150f
        val sweepTotal = 240f
        val strokeWidth = 18.dp.toPx()
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

        fun sweepFor(value: Float) = sweepTotal * (value / GAUGE_MAX).coerceIn(0f, 1f)

        // Кольорові зони відповідно до порогів VibrationState
        val zones = listOf(
            0f to VibrationState.ELEVATED_THRESHOLD to ColorNormal,
            VibrationState.ELEVATED_THRESHOLD to VibrationState.CRITICAL_THRESHOLD to ColorElevated,
            VibrationState.CRITICAL_THRESHOLD to GAUGE_MAX to ColorCritical
        )
        for ((range, color) in zones) {
            val (from, to) = range
            drawArc(
                color = color,
                startAngle = startAngle + sweepFor(from),
                sweepAngle = sweepFor(to) - sweepFor(from),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth)
            )
        }

        // Стрілка
        val angleRad = Math.toRadians((startAngle + sweepFor(rms)).toDouble())
        val radius = arcSize.width / 2 - strokeWidth
        val end = Offset(
            center.x + (radius * cos(angleRad)).toFloat(),
            center.y + (radius * sin(angleRad)).toFloat()
        )
        drawLine(trackColor, center, end, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(trackColor, radius = 6.dp.toPx(), center = center)
    }
}

@Composable
private fun TiltBubble(x: Float, y: Float, color: Color, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val radius = size.minDimension / 2
        drawCircle(lineColor, radius = radius, style = Stroke(2.dp.toPx()))
        drawCircle(lineColor, radius = radius / 2, style = Stroke(1.dp.toPx()))
        drawLine(lineColor, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y))
        drawLine(lineColor, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius))

        // Точка зміщується пропорційно проекціям гравітації на осі X та Y:
        // 9.81 м/с² по осі відповідає нахилу пристрою на 90°, у центрі — пристрій лежить горизонтально
        val g = 9.81f
        val bubbleRadius = 12.dp.toPx()
        val maxOffset = radius - bubbleRadius
        val bx = center.x - (x / g).coerceIn(-1f, 1f) * maxOffset
        val by = center.y + (y / g).coerceIn(-1f, 1f) * maxOffset
        drawCircle(color, radius = bubbleRadius, center = Offset(bx, by))
    }
}

/** Графік динамічного прискорення з лініями порогів. */
@Composable
private fun HistoryCard(s: VibrationSnapshot) {
    val lineColor = MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Динамічне прискорення (останні відліки)", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                fun yFor(v: Float) = size.height * (1f - (v / GAUGE_MAX).coerceIn(0f, 1f))

                drawLine(ColorElevated, Offset(0f, yFor(VibrationState.ELEVATED_THRESHOLD)),
                    Offset(size.width, yFor(VibrationState.ELEVATED_THRESHOLD)), strokeWidth = 2f)
                drawLine(ColorCritical, Offset(0f, yFor(VibrationState.CRITICAL_THRESHOLD)),
                    Offset(size.width, yFor(VibrationState.CRITICAL_THRESHOLD)), strokeWidth = 2f)

                val points = s.history
                if (points.size >= 2) {
                    val step = size.width / (points.size - 1)
                    val path = Path().apply {
                        moveTo(0f, yFor(points[0]))
                        for (i in 1 until points.size) lineTo(i * step, yFor(points[i]))
                    }
                    drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
                }
            }
            Text(
                "Пороги: %.1f м/с² (підвищена), %.1f м/с² (критична)".format(
                    VibrationState.ELEVATED_THRESHOLD, VibrationState.CRITICAL_THRESHOLD
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun WearCard(s: VibrationSnapshot, onReset: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Накопичений знос (умовний): %.1f %%".format(s.wearPercent),
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { s.wearPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = s.state.color()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onReset) { Text("Скинути (після ремонту)") }
        }
    }
}

@Composable
private fun SimulationCard(
    enabled: Boolean,
    level: Float,
    onEnabledChange: (Boolean) -> Unit,
    onLevelChange: (Float) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Імітація зносу обладнання", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Text(
                "Додає до даних сенсора синтетичну вібрацію 7 Гц (дисбаланс) і шум (люфт).",
                style = MaterialTheme.typography.bodySmall
            )
            if (enabled) {
                Text("Рівень зносу: %.0f %%".format(level * 100))
                Slider(value = level, onValueChange = onLevelChange)
            }
        }
    }
}

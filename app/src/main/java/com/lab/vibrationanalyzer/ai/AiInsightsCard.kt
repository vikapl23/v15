package com.lab.vibrationanalyzer.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lab.vibrationanalyzer.analysis.DemoPattern

private val ColorCalm = Color(0xFF2E7D32)
private val ColorTouch = Color(0xFFF9A825)
private val ColorMachine = Color(0xFFEF6C00)
private val ColorTamper = Color(0xFFC62828)

fun VibrationClass.color(): Color = when (this) {
    VibrationClass.CALM -> ColorCalm
    VibrationClass.TOUCH -> ColorTouch
    VibrationClass.MACHINE -> ColorMachine
    VibrationClass.TAMPER -> ColorTamper
}

/**
 * ЛР6. Блок «AI Insights» на головному екрані: прогноз моделі в реальному часі.
 * Чиста функція: State на вході, дії на виході.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiInsightsCard(
    state: AiState,
    demoPattern: DemoPattern,
    onAction: (AiAction) -> Unit,
    onPatternChange: (DemoPattern) -> Unit
) {
    val insight = state.insight
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🤖 AI Insights", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("On-device · TensorFlow Lite · без інтернету", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = state.enabled,
                    enabled = state.modelLoaded,
                    onCheckedChange = { onAction(AiAction.EnabledToggled(it)) }
                )
            }

            when {
                state.error != null -> Text(state.error, color = ColorTamper)
                !state.enabled -> Text("ШІ-аналіз вимкнено: працюють лише порогові правила ЛР4/ЛР5")
                insight == null -> {
                    Text("Накопичення вікна даних: ${state.bufferedFrames}/${state.windowFrames} кадрів…")
                    LinearProgressIndicator(
                        progress = { state.bufferedFrames / state.windowFrames.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> {
                    Text(
                        "Current Activity: ${insight.top.emoji} ${insight.headline}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = insight.top.color()
                    )
                    Text(insight.top.description, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    VibrationClass.entries.forEach { c ->
                        ProbabilityRow(c, insight.probabilities[c] ?: 0f, highlighted = c == insight.top)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (insight.anomaly) StatusChip("🚨 Аномалія → сирена і прожектор (ЛР4)", ColorTamper)
                        if (insight.highActivity) StatusChip("⚡ Прискорений запис у БД (ЛР5)", ColorMachine)
                        if (!insight.anomaly && !insight.highActivity) StatusChip("✓ Звичайний режим", ColorCalm)
                    }
                    Text(
                        "Прогноз №${insight.count} · inference %.2f мс (сер. %.2f) · ${state.thread}".format(
                            insight.inferenceMs, state.avgInferenceMs
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (state.modelLoaded) {
                Text(state.modelDescription, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }

            Spacer(Modifier.height(4.dp))
            Text("Демо-патерн (додається до сенсора емулятора):", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DemoPattern.entries.forEach { p ->
                    FilterChip(
                        selected = p == demoPattern,
                        onClick = { onPatternChange(p) },
                        label = { Text(p.title) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProbabilityRow(c: VibrationClass, p: Float, highlighted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${c.emoji} ${c.title}",
            modifier = Modifier.width(170.dp),
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium
        )
        LinearProgressIndicator(
            progress = { p.coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f),
            color = c.color()
        )
        Text(
            "%3d%%".format(Math.round(p * 100)),
            modifier = Modifier.width(48.dp).padding(start = 6.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    AssistChip(
        onClick = {},
        label = { Text(text, color = Color.White) },
        colors = AssistChipDefaults.assistChipColors(containerColor = color)
    )
}

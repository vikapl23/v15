package com.lab.vibrationanalyzer.analytics

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lab.vibrationanalyzer.analysis.ChartPoint
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Під цим ключем передаємо в модель графіка час кожної точки (для підписів осі X). */
private val TimestampsKey = ExtraStore.Key<List<Long>>()

private val LineColor = Color(0xFF1565C0)
private val ThresholdColor = Color(0xFFC62828)

/**
 * ЛР5. Лінійний графік (LineChart) на бібліотеці Vico.
 *
 * Вісь X — час виміру (підписи HH:mm:ss), вісь Y — RMS вібрації, м/с².
 * Червона пунктирна лінія — поріг «Критична» (1,5 м/с²), при якому ЛР4 вмикає актуатори.
 *
 * Як працює оновлення:
 *  1. Список [points] приходить зі State (його оновлює Flow з Room).
 *  2. LaunchedEffect(points) перезапускається при кожній зміні списку і передає нові
 *     дані в CartesianChartModelProducer.runTransaction { lineSeries { … } }.
 *  3. Vico плавно анімує перехід від старої лінії до нової.
 *
 * По X передаємо номери точок 0..N-1, а не мілісекунди: записи йдуть нерівномірно
 * (лише при суттєвій зміні), і з «сирим» часом Vico розтягнув би графік на тисячі кроків.
 * Справжній час кожної точки лежить у ExtraStore і підставляється в підписи осі.
 */
@Composable
fun VibrationLineChart(
    points: List<ChartPoint>,
    threshold: Float,
    modifier: Modifier = Modifier
) {
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(points) {
        if (points.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineSeries { series(points.indices.toList(), points.map { it.value }) }
            extras { it[TimestampsKey] = points.map { p -> p.timestampMs } }
        }
    }

    val timeFormatter = remember {
        val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        CartesianValueFormatter { context, x, _ ->
            val time = context.model.extraStore.getOrNull(TimestampsKey)?.getOrNull(x.toInt())
            if (time == null) " " else format.format(Date(time))
        }
    }
    val valueFormatter = remember { CartesianValueFormatter.decimal() }

    ProvideVicoTheme(rememberM3VicoTheme()) {
        // Компоненти «запам'ятовуємо» (remember), щоб при кожній новій точці
        // не створювати графік заново, а лише оновлювати дані
        val line = LineCartesianLayer.rememberLine(
            fill = remember { LineCartesianLayer.LineFill.single(fill(LineColor)) },
            areaFill = remember {
                LineCartesianLayer.AreaFill.single(fill(LineColor.copy(alpha = 0.18f)))
            }
        )
        val lineProvider = remember(line) { LineCartesianLayer.LineProvider.series(line) }
        val axisTitle = rememberTextComponent(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textSize = 11.sp
        )
        val thresholdLine = rememberLineComponent(fill = fill(ThresholdColor), thickness = 1.5.dp)
        val thresholdLabel = rememberTextComponent(color = ThresholdColor, textSize = 10.sp)
        val decorations = remember(threshold, thresholdLine, thresholdLabel) {
            listOf(
                HorizontalLine(
                    y = { threshold.toDouble() },
                    line = thresholdLine,
                    labelComponent = thresholdLabel,
                    label = { "поріг %.1f".format(threshold) }
                )
            )
        }

        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = lineProvider,
                    // Y від нуля: RMS не буває від'ємним, і «хвилі» видно в правильному масштабі
                    rangeProvider = remember { CartesianLayerRangeProvider.fixed(minY = 0.0) }
                ),
                startAxis = VerticalAxis.rememberStart(
                    valueFormatter = valueFormatter,
                    titleComponent = axisTitle,
                    title = "RMS, м/с²"
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = timeFormatter,
                    // підпис часу під кожною 10-ю точкою, щоб написи не накладались
                    itemPlacer = remember { HorizontalAxis.ItemPlacer.aligned(spacing = { 10 }) },
                    titleComponent = axisTitle,
                    title = "Час"
                ),
                decorations = decorations
            ),
            modelProducer = modelProducer,
            modifier = modifier.fillMaxWidth().height(260.dp),
            // Усі точки одразу на екрані: без прокрутки і масштабування
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content)
        )
    }
}

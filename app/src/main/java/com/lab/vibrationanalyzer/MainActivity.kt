package com.lab.vibrationanalyzer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lab.vibrationanalyzer.ai.AiViewModel
import com.lab.vibrationanalyzer.analytics.AnalyticsScreen
import com.lab.vibrationanalyzer.analytics.SensorViewModel
import com.lab.vibrationanalyzer.cloud.CloudEvent
import com.lab.vibrationanalyzer.cloud.CloudScreen
import com.lab.vibrationanalyzer.cloud.CloudViewModel
import com.lab.vibrationanalyzer.control.ControlEvent
import com.lab.vibrationanalyzer.control.ControlScreen
import com.lab.vibrationanalyzer.control.ControlViewModel
import com.lab.vibrationanalyzer.hub.HubScreen
import com.lab.vibrationanalyzer.hub.HubViewModel
import com.lab.vibrationanalyzer.monitor.MonitorAction
import com.lab.vibrationanalyzer.monitor.MonitorScreen
import com.lab.vibrationanalyzer.monitor.MonitorViewModel
import com.lab.vibrationanalyzer.ui.theme.VibrationAnalyzerTheme

/**
 * Показує екрани і перетворює події життєвого циклу на Action. Логіки тут немає.
 */
class MainActivity : ComponentActivity() {

    // Ті самі ViewModel переживають поворот екрана
    private val monitorVm: MonitorViewModel by viewModels()
    private val hubVm: HubViewModel by viewModels()
    private val cloudVm: CloudViewModel by viewModels()   // ЛР3
    private val controlVm: ControlViewModel by viewModels()   // ЛР4
    private val sensorVm: SensorViewModel by viewModels()     // ЛР5: сенсор → Room → графік
    private val aiVm: AiViewModel by viewModels()             // ЛР6: модель TFLite → AI Insights

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VibrationAnalyzerTheme {
                AppScaffold(monitorVm, hubVm, cloudVm, controlVm, sensorVm, aiVm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        monitorVm.onAction(MonitorAction.Start)   // екран видно → слухаємо сенсор
    }

    override fun onPause() {
        monitorVm.onAction(MonitorAction.Stop)    // екран сховано → відписуємось, економимо батарею
        super.onPause()
    }
}

/**
 * Нижня навігація: Монітор (ЛР1 + блок AI Insights ЛР6), Смарт-хаб (ЛР2), Хмара (ЛР3),
 * Керування (ЛР4), Аналітика (ЛР5).
 */
@Composable
private fun AppScaffold(
    monitorVm: MonitorViewModel,
    hubVm: HubViewModel,
    cloudVm: CloudViewModel,
    controlVm: ControlViewModel,
    sensorVm: SensorViewModel,
    aiVm: AiViewModel
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHost = remember { SnackbarHostState() }

    // ЛР3: повідомлення хмари («Немає інтернету…») показуємо як Snackbar на будь-якій вкладці
    LaunchedEffect(Unit) {
        cloudVm.events.collect { event ->
            when (event) {
                is CloudEvent.ShowSnackbar -> snackbarHost.showSnackbar(event.text)
            }
        }
    }
    // ЛР4: повідомлення про тривогу теж видно на будь-якій вкладці
    LaunchedEffect(Unit) {
        controlVm.events.collect { event ->
            when (event) {
                is ControlEvent.ShowSnackbar -> snackbarHost.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("📈") },
                    label = { Text("Монітор") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text("📡") },
                    label = { Text("Смарт-хаб") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Text("☁️") },
                    label = { Text("Хмара") }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Text("🎛️") },
                    label = { Text("Керування") }
                )
                NavigationBarItem(
                    selected = tab == 4,
                    onClick = { tab = 4 },
                    icon = { Text("📊") },
                    label = { Text("Аналітика") }
                )
            }
        }
    ) { innerPadding ->
        when (tab) {
            0 -> MonitorScreen(monitorVm, aiVm, Modifier.padding(innerPadding))
            1 -> HubScreen(hubVm, Modifier.padding(innerPadding))
            2 -> CloudScreen(cloudVm, Modifier.padding(innerPadding))
            3 -> ControlScreen(controlVm, Modifier.padding(innerPadding))
            else -> AnalyticsScreen(sensorVm, Modifier.padding(innerPadding))
        }
    }
}

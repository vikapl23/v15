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
import com.lab.vibrationanalyzer.cloud.CloudEvent
import com.lab.vibrationanalyzer.cloud.CloudScreen
import com.lab.vibrationanalyzer.cloud.CloudViewModel
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VibrationAnalyzerTheme {
                AppScaffold(monitorVm, hubVm, cloudVm)
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

/** Нижня навігація між екранами: Монітор (ЛР1), Смарт-хаб (ЛР2), Хмара (ЛР3). */
@Composable
private fun AppScaffold(monitorVm: MonitorViewModel, hubVm: HubViewModel, cloudVm: CloudViewModel) {
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
            }
        }
    ) { innerPadding ->
        when (tab) {
            0 -> MonitorScreen(monitorVm, Modifier.padding(innerPadding))
            1 -> HubScreen(hubVm, Modifier.padding(innerPadding))
            else -> CloudScreen(cloudVm, Modifier.padding(innerPadding))
        }
    }
}

package com.lab.vibrationanalyzer.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.GsonBuilder
import com.lab.vibrationanalyzer.SmartVibrationApp
import com.lab.vibrationanalyzer.helpers.cloud.CloudConfig
import com.lab.vibrationanalyzer.repository.CloudRecord
import com.lab.vibrationanalyzer.repository.OfferResult
import com.lab.vibrationanalyzer.repository.toSensorData
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ЛР3. «Мозок» хмарної синхронізації.
 *
 * Шлях даних: SensorReader → MonitorViewModel → VibrationRepository.latest →
 * ЦЯ ViewModel → CloudRepository.offer() → Retrofit → хмара.
 *
 * Усі корутини запускаються у viewModelScope (це CoroutineScope, прив'язаний до ViewModel),
 * а сам HTTP-запит виконується на Dispatchers.IO, тому UI-потік ніколи не блокується.
 */
class CloudViewModel(app: Application) : AndroidViewModel(app) {

    private val smartApp = app as SmartVibrationApp
    private val vibrationRepository = smartApp.repository
    private val cloudRepository = smartApp.cloudRepository
    private val network = smartApp.networkMonitor
    private val config = smartApp.cloudConfig
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val _state = MutableStateFlow(
        CloudState(
            isOnline = network.isOnline.value,
            intervalSec = (cloudRepository.policy.intervalMs / 1000).toInt(),
            delta = cloudRepository.policy.delta,
            deviceId = config.deviceId,
            usingMockApi = config.mode == CloudConfig.Mode.MOCK_REST
        )
    )
    val state: StateFlow<CloudState> = _state.asStateFlow()

    private val _events = Channel<CloudEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Щоб не показувати те саме повідомлення кожні 5 секунд
    private var offlineNotified = false
    private var errorNotified = false

    init {
        // 1. Стан репозиторію → State екрана
        viewModelScope.launch {
            cloudRepository.state.collect { sync ->
                val lastSent = sync.log.firstOrNull { it.result == CloudRecord.Result.SENT }
                _state.update {
                    it.copy(sync = sync, lastJson = lastSent?.let { r -> gson.toJson(r.data) } ?: it.lastJson)
                }
            }
        }

        // 2. Стан мережі: зникла → Snackbar + Pending; з'явилась → досилаємо чергу
        viewModelScope.launch {
            network.isOnline.collect { online ->
                _state.update { it.copy(isOnline = online) }
                if (!online) {
                    cloudRepository.onNetworkLost()
                    notifyOffline()
                } else if (offlineNotified) {
                    offlineNotified = false
                    val sent = cloudRepository.flushPending()
                    send("Інтернет відновлено" + if (sent > 0) ". Досилано записів: $sent" else "")
                }
            }
        }

        // 3. Нові відліки сенсора → у хмару (репозиторій сам вирішує, чи вже час слати)
        viewModelScope.launch {
            vibrationRepository.latest.filterNotNull().collect { reading ->
                if (!_state.value.syncEnabled) return@collect
                when (val result = cloudRepository.offer(reading.toSensorData(config.deviceId))) {
                    OfferResult.Queued -> notifyOffline()
                    is OfferResult.Failed -> if (!errorNotified) {
                        errorNotified = true
                        send("Хмара недоступна: ${result.error}. Запис збережено зі статусом Pending")
                    }
                    is OfferResult.Sent -> errorNotified = false
                    OfferResult.Skipped -> Unit
                }
            }
        }
    }

    // ЄДИНИЙ вхід у ViewModel — всі дії приходять сюди
    fun onAction(action: CloudAction) {
        when (action) {
            is CloudAction.SyncToggled -> _state.update { it.copy(syncEnabled = action.enabled) }
            is CloudAction.IntervalChanged -> {
                val sec = action.seconds.coerceIn(1, 60)
                cloudRepository.policy.intervalMs = sec * 1000L
                _state.update { it.copy(intervalSec = sec) }
            }
            is CloudAction.DeltaChanged -> {
                val d = action.delta.coerceIn(0.1f, 10f)
                cloudRepository.policy.delta = d
                _state.update { it.copy(delta = d) }
            }
            CloudAction.RetryClick -> retry()
        }
    }

    private fun retry() {
        viewModelScope.launch {
            if (!network.isOnline.value) {
                send("Немає інтернету. Записів у черзі: ${_state.value.sync.pendingCount}")
                return@launch
            }
            val sent = cloudRepository.flushPending()
            send("Досилано записів: $sent")
        }
    }

    private fun notifyOffline() {
        if (offlineNotified) return
        offlineNotified = true
        send("Немає інтернету: дані зберігаються зі статусом Pending")
    }

    private fun send(text: String) {
        viewModelScope.launch { _events.send(CloudEvent.ShowSnackbar(text)) }
    }
}

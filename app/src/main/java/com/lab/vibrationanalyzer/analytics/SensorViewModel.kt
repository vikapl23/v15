package com.lab.vibrationanalyzer.analytics

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lab.vibrationanalyzer.SmartVibrationApp
import com.lab.vibrationanalyzer.analysis.ChartDownsampler
import com.lab.vibrationanalyzer.analysis.ChartPoint
import com.lab.vibrationanalyzer.analysis.RecordingMode
import com.lab.vibrationanalyzer.analysis.RecordingPolicy
import com.lab.vibrationanalyzer.repository.VibrationReading
import com.lab.vibrationanalyzer.repository.local.SensorRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ЛР5. SensorViewModel — посередник між сенсором і базою даних (MVVM).
 *
 * Шлях запису:
 *   SensorEventListener.onSensorChanged (SensorReader, ЛР1) → MonitorViewModel (RMS) →
 *   VibrationRepository.latest → ЦЯ ViewModel → RecordingPolicy («чи суттєва зміна?») →
 *   SensorRecordRepository.save() → withContext(Dispatchers.IO) → SensorRecordDao.insert() → SQLite
 *
 * Шлях читання (динамічний графік):
 *   SQLite → SensorRecordDao.observeLast(50): Flow → SensorRecordRepository → ЦЯ ViewModel →
 *   SensorState.points → AnalyticsScreen → LineChart (Vico)
 *
 * Room сам перезапускає Flow-запит після кожного insert, тому нова точка з'являється
 * на графіку без жодного «оновити» — це і є оновлення в реальному часі.
 *
 * ЛР6: якщо модель TensorFlow Lite бачить «Робочу вібрацію» або «Спробу злому»
 * (AiInsight.highActivity, аналог «Біг»), RecordingPolicy переходить у прискорений режим:
 * пишемо до 20 разів/с замість 5, «пульс» — раз на 1 с замість 5 с.
 *
 * ViewModel створюється в MainActivity (by viewModels()), тому запис іде,
 * поки відкритий додаток, на будь-якій вкладці, а не лише на «Аналітиці».
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SensorViewModel(app: Application) : AndroidViewModel(app) {

    private val smartApp = app as SmartVibrationApp
    private val live = smartApp.repository                  // «живі» дані сенсора (пам'ять)
    private val records = smartApp.sensorRecordRepository   // історія (Room)
    private val policy = RecordingPolicy()

    private val _state = MutableStateFlow(SensorState())
    val state: StateFlow<SensorState> = _state.asStateFlow()

    private val _events = Channel<SensorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val range = MutableStateFlow(ChartRange.LAST_N)

    init {
        // 0. Прибирання: записи, старші за добу, видаляємо (фонова операція, Dispatchers.IO)
        viewModelScope.launch {
            runCatching { records.deleteOlderThan(RETENTION_MS) }
                .onSuccess { if (it > 0) Log.d(TAG, "видалено старих записів: $it") }
                .onFailure { Log.e(TAG, "помилка очищення БД", it) }
        }

        // 1. Сенсор → БД
        viewModelScope.launch {
            live.latest.filterNotNull().collect(::onReading)
        }
        viewModelScope.launch {
            live.actuatorActive.collect { active -> _state.update { it.copy(actuatorActive = active) } }
        }

        // 1б. ЛР6: прогноз моделі → частота запису в БД
        viewModelScope.launch {
            live.aiInsight.collect { insight ->
                val boosted = insight?.highActivity == true
                if (boosted != policy.boosted) {
                    Log.d(TAG, "ШІ: ${insight?.headline ?: "вимкнено"} → запис " +
                        if (boosted) "ПРИСКОРЕНО" else "звичайний")
                }
                policy.boosted = boosted
                _state.update {
                    it.copy(
                        aiBoosted = boosted,
                        aiClass = insight?.headline,
                        writeIntervalMs = currentInterval()
                    )
                }
            }
        }

        // 2. БД → графік. flatMapLatest: при зміні діапазону стара підписка на Room скасовується
        viewModelScope.launch {
            combine(range, ticker(MINUTE_MS)) { r, now -> r to now }
                .flatMapLatest { (r, now) -> chartPoints(r, now) }
                .collect { points -> _state.update { it.copy(points = points) } }
        }

        // 3. Зведення для карток під графіком
        viewModelScope.launch {
            records.observeCount().collect { n -> _state.update { it.copy(totalRecords = n) } }
        }
        viewModelScope.launch {
            ticker(MINUTE_MS)
                .flatMapLatest { now -> records.observeStats(now - HOUR_MS) }
                .collect { stats -> _state.update { it.copy(hourStats = stats) } }
        }
        viewModelScope.launch {
            records.observeTriggered(HISTORY_SIZE).collect { list ->
                _state.update { it.copy(triggeredHistory = list.map { r -> r.toPoint() }) }
            }
        }
    }

    // ЄДИНИЙ вхід у ViewModel — всі дії приходять сюди
    fun onAction(action: SensorAction) {
        when (action) {
            is SensorAction.RecordingToggled -> {
                _state.update { it.copy(recordingEnabled = action.enabled) }
                if (action.enabled) policy.reset()   // перший відлік після паузи — одразу в БД
            }
            is SensorAction.ModeChanged -> {
                policy.mode = action.mode
                _state.update { it.copy(mode = action.mode, writeIntervalMs = currentInterval()) }
            }
            is SensorAction.RangeChanged -> {
                range.value = action.range
                _state.update { it.copy(range = action.range) }
            }
            SensorAction.ClearClick -> clear()
        }
    }

    /** Викликається на кожен відлік сенсора (~50 разів/с). У БД потрапляє лише частина. */
    private fun onReading(reading: VibrationReading) {
        _state.update { it.copy(liveValue = reading.rms) }
        if (!_state.value.recordingEnabled) return

        val triggered = live.actuatorActive.value
        if (!policy.shouldRecord(reading.rms, triggered, reading.timestampMs)) return

        // Запис — у корутині: save() перемикається на Dispatchers.IO, UI-потік вільний
        viewModelScope.launch {
            runCatching { records.save(reading.rms, triggered, reading.timestampMs) }
                .onSuccess { id ->
                    Log.d(TAG, "insert id=$id value=%.3f triggered=$triggered".format(reading.rms))
                    _state.update {
                        it.copy(savedThisSession = it.savedThisSession + 1, lastSavedAtMs = reading.timestampMs)
                    }
                }
                .onFailure { Log.e(TAG, "помилка запису в БД", it) }
        }
    }

    /** Інтервал запису, що діє зараз (для підпису на екрані). */
    private fun currentInterval(): Long =
        if (policy.mode == RecordingMode.TIMER) policy.currentTimerIntervalMs else policy.currentMinIntervalMs

    private fun chartPoints(range: ChartRange, now: Long): Flow<List<ChartPoint>> = when (range) {
        ChartRange.LAST_N -> records.observeLast(CHART_POINTS).map { list -> list.map { it.toPoint() } }
        ChartRange.LAST_HOUR -> records.observeSince(now - HOUR_MS).map { list ->
            ChartDownsampler.downsample(list.map { it.toPoint() }, HOUR_CHART_POINTS)
        }
    }

    private fun clear() {
        viewModelScope.launch {
            val deleted = runCatching { records.clear() }.getOrElse {
                Log.e(TAG, "помилка очищення БД", it)
                send("Не вдалося очистити БД: ${it.message}")
                return@launch
            }
            policy.reset()
            _state.update { it.copy(savedThisSession = 0, lastSavedAtMs = null) }
            send("Видалено записів: $deleted")
        }
    }

    private fun send(text: String) {
        viewModelScope.launch { _events.send(SensorEvent.ShowMessage(text)) }
    }

    /** Потік «поточний час» раз на [periodMs]: зсуває вікно «остання година». */
    private fun ticker(periodMs: Long): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(periodMs)
        }
    }

    private fun SensorRecord.toPoint() = ChartPoint(timestamp, sensorValue, actionTriggered)

    companion object {
        /** Скільки останніх записів показує графік (обмеження з методички — не перевантажувати рендеринг). */
        const val CHART_POINTS = 50
        const val HOUR_CHART_POINTS = 60          // година → 60 точок, приблизно точка на хвилину
        const val HISTORY_SIZE = 10               // скільки спрацювань показувати в журналі
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val RETENTION_MS = 24 * HOUR_MS     // зберігаємо історію за добу
        private const val TAG = "ROOM_DB"
    }
}

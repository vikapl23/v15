package com.lab.vibrationanalyzer.ai

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lab.vibrationanalyzer.SmartVibrationApp
import com.lab.vibrationanalyzer.repository.VibrationReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ЛР6. «Мозок» блоку AI Insights: сирі дані сенсора → вікно → модель TFLite → прогноз.
 *
 * Шлях даних:
 *   SensorReader (ЛР1) → MonitorViewModel → VibrationRepository.samples (кожен відлік) →
 *   ЦЯ ViewModel → AiPipeline (фільтр гравітації, кадри 50 мс, кільцевий буфер 20 кадрів) →
 *   withContext(Dispatchers.Default) { ActivityClassifier.predict() } → InsightSmoother →
 *   VibrationRepository.aiInsight → ControlViewModel (актуатор, ЛР4) і SensorViewModel (запис у БД, ЛР5)
 *
 * Inference — CPU-інтенсивна задача, тому вона йде в пулі потоків Dispatchers.Default.
 * UI-потік лише кладе відлік у буфер (мікросекунди) і малює результат.
 */
class AiViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as SmartVibrationApp).repository
    private val pipeline = AiPipeline()
    private val smoother = InsightSmoother()

    private val classifier: IVibrationClassifier? = runCatching<IVibrationClassifier> {
        ActivityClassifier(app)
    }.onFailure { Log.e(TAG, "не вдалося завантажити модель", it) }.getOrNull()

    private val _state = MutableStateFlow(
        AiState(
            modelLoaded = classifier != null,
            modelDescription = classifier?.description.orEmpty(),
            error = if (classifier == null) "Модель ${ModelSpec.MODEL_FILE} не завантажилась (див. Logcat AI_INSIGHT)" else null,
            windowFrames = pipeline.windowFrames
        )
    )
    val state: StateFlow<AiState> = _state.asStateFlow()

    private var busy = false               // поки модель рахує, нові вікна пропускаємо
    private var totalInferenceMs = 0.0
    private var lastLoggedClass: VibrationClass? = null

    init {
        if (classifier != null) {
            Log.d(TAG, "модель завантажено: ${classifier.description}")
            viewModelScope.launch {
                repository.samples.collect(::onSample)
            }
        }
    }

    fun onAction(action: AiAction) {
        when (action) {
            is AiAction.EnabledToggled -> {
                _state.update { it.copy(enabled = action.enabled, insight = null, bufferedFrames = 0) }
                pipeline.reset()
                smoother.reset()
                repository.publishAiInsight(null)   // вимкнули ШІ → актуатор і запис повертаються до звичайних правил
            }
        }
    }

    /** Викликається на КОЖЕН відлік (~50/с) у головному потоці — тут лише дешеві операції. */
    private fun onSample(reading: VibrationReading) {
        if (!_state.value.enabled) return
        val classifier = classifier ?: return
        val window = pipeline.push(reading.x, reading.y, reading.z, reading.sensorTimeMs)
        if (window == null) {
            if (_state.value.bufferedFrames != pipeline.bufferedFrames) {
                _state.update { it.copy(bufferedFrames = pipeline.bufferedFrames) }
            }
            return
        }
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                // Inference у фоновому потоці (пул Dispatchers.Default = кількість ядер CPU)
                val (probabilities, ms, thread) = withContext(Dispatchers.Default) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val p = classifier.predict(window)
                    Triple(p, (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000f, Thread.currentThread().name)
                }
                if (!_state.value.enabled) return@launch
                val insight = smoother.update(probabilities, ms, System.currentTimeMillis())
                totalInferenceMs += ms
                _state.update {
                    it.copy(
                        insight = insight,
                        bufferedFrames = pipeline.bufferedFrames,
                        avgInferenceMs = (totalInferenceMs / insight.count).toFloat(),
                        thread = thread
                    )
                }
                repository.publishAiInsight(insight)
                logIfChanged(insight)
            } catch (e: Exception) {
                Log.e(TAG, "помилка inference", e)
                _state.update { it.copy(error = "Помилка inference: ${e.message}") }
            } finally {
                busy = false
            }
        }
    }

    private fun logIfChanged(insight: AiInsight) {
        if (insight.top == lastLoggedClass) return
        lastLoggedClass = insight.top
        Log.d(
            TAG,
            "Current Activity: ${insight.headline} · " +
                insight.probabilities.entries.joinToString { "${it.key.label}=%.2f".format(it.value) } +
                " · anomaly=${insight.anomaly} highActivity=${insight.highActivity}" +
                " · %.2f мс".format(insight.inferenceMs)
        )
    }

    override fun onCleared() {
        classifier?.close()   // звільняємо нативну пам'ять інтерпретатора
        super.onCleared()
    }

    private companion object {
        const val TAG = "AI_INSIGHT"
    }
}

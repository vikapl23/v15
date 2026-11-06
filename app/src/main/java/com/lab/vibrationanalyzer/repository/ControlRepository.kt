package com.lab.vibrationanalyzer.repository

import com.lab.vibrationanalyzer.helpers.cloud.ChannelMode
import com.lab.vibrationanalyzer.helpers.cloud.IActuatorFeedback
import com.lab.vibrationanalyzer.helpers.cloud.ICloudCommandSource
import com.lab.vibrationanalyzer.helpers.cloud.ControlUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** ЛР4. Стан каналу команд з хмари. */
enum class ChannelStatus(val title: String) {
    NOT_CONFIGURED("Firebase не налаштовано"),
    STOPPED("Вимкнено"),
    CONNECTING("Підключення…"),
    CONNECTED("Підключено, чекаємо команд"),
    RECONNECTING("Немає зв'язку, повторна спроба…")
}

/** Один рядок журналу керування. */
data class ControlLogEntry(val timeMs: Long, val text: String, val isError: Boolean = false)

/** Усе, що репозиторій знає про дистанційне керування. */
data class RemoteControlState(
    val status: ChannelStatus = ChannelStatus.STOPPED,
    val mode: ChannelMode = ChannelMode.EVENT_DRIVEN,
    val endpoint: String = "",
    val feedbackEndpoint: String = "",
    val command: ControlCommand = ControlCommand(),
    val lastCommandAtMs: Long? = null,
    val lastRaw: String? = null,
    val commandsReceived: Int = 0,
    /** Скільки HTTP-запитів зроблено: SSE — 1 на з'єднання, Polling — 1 на кожне опитування. */
    val httpRequests: Int = 0,
    val feedbackSent: Int = 0,
    val lastFeedbackOk: Boolean? = null,
    val lastError: String? = null,
    val log: List<ControlLogEntry> = emptyList()   // найновіші зверху
)

/**
 * ЛР4. Репозиторій дистанційного керування (Cloud-to-Device + зворотний зв'язок).
 *
 * ViewModel не знає, ЯК приходять команди: через потік подій чи через опитування.
 * Вона лише викликає [listen] і читає [state]. Перепідключення після обриву
 * (зник інтернет, сервер закрив потік) теж робить репозиторій.
 */
class ControlRepository(
    private val sources: Map<ChannelMode, ICloudCommandSource>,
    private val feedback: IActuatorFeedback?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelaysMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000),
    private val logSize: Int = 30,
    private val logcat: (String) -> Unit = {}
) {
    val isConfigured: Boolean get() = sources.isNotEmpty()

    /** Які режими доступні (SDK — лише якщо є google-services.json). */
    val availableModes: List<ChannelMode> get() = ChannelMode.entries.filter { it in sources }

    private val _state = MutableStateFlow(
        RemoteControlState(
            status = if (sources.isEmpty()) ChannelStatus.NOT_CONFIGURED else ChannelStatus.STOPPED,
            mode = sources.keys.firstOrNull() ?: ChannelMode.EVENT_DRIVEN,
            endpoint = sources.values.firstOrNull()?.endpoint.orEmpty(),
            feedbackEndpoint = feedback?.endpoint.orEmpty()
        )
    )
    val state: StateFlow<RemoteControlState> = _state.asStateFlow()

    /**
     * Слухати команди, поки корутину не скасують. Викликається з viewModelScope.launch.
     * Після помилки чекає 1 → 2 → 5 → 10 с і пробує знову.
     */
    suspend fun listen(mode: ChannelMode) {
        val source = sources[mode] ?: return
        _state.update {
            it.copy(mode = mode, endpoint = source.endpoint, status = ChannelStatus.CONNECTING, lastError = null)
        }
        addLog("канал: ${mode.title}")
        var attempt = 0
        try {
            while (true) {
                try {
                    source.updates().collect { update ->
                        attempt = 0   // зв'язок є — лічильник спроб скидаємо
                        onUpdate(mode, update)
                    }
                    throw IllegalStateException("канал завершився")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = e.message ?: e.javaClass.simpleName
                    val wait = retryDelaysMs[attempt.coerceAtMost(retryDelaysMs.lastIndex)]
                    attempt++
                    _state.update { it.copy(status = ChannelStatus.RECONNECTING, lastError = message) }
                    addLog("обрив: $message. Повтор через ${wait / 1000} с", isError = true)
                    logcat("канал команд: $message, повтор через $wait мс")
                    delay(wait)
                    _state.update { it.copy(status = ChannelStatus.CONNECTING) }
                }
            }
        } finally {
            if (_state.value.status != ChannelStatus.NOT_CONFIGURED) {
                _state.update { it.copy(status = ChannelStatus.STOPPED) }
            }
        }
    }

    private fun onUpdate(mode: ChannelMode, update: ControlUpdate) {
        when (update) {
            ControlUpdate.Connected -> {
                _state.update {
                    it.copy(
                        status = ChannelStatus.CONNECTED,
                        lastError = null,
                        // SSE і SDK: одне постійне з'єднання = один запит; Polling рахує кожне опитування
                        httpRequests = if (mode != ChannelMode.POLLING) it.httpRequests + 1 else it.httpRequests
                    )
                }
                addLog("підключено (${mode.short})")
            }
            is ControlUpdate.Disconnected -> {
                _state.update { it.copy(status = ChannelStatus.RECONNECTING, lastError = update.reason) }
                addLog("зв'язок втрачено: ${update.reason}", isError = true)
            }
            ControlUpdate.Polled -> _state.update { it.copy(httpRequests = it.httpRequests + 1) }
            is ControlUpdate.Command -> {
                val changed = update.command != _state.value.command
                _state.update {
                    it.copy(
                        command = update.command,
                        lastCommandAtMs = clock(),
                        lastRaw = update.raw,
                        commandsReceived = it.commandsReceived + 1
                    )
                }
                if (changed) addLog("команда: ${update.command.describe()}")
                logcat("команда з хмари: ${update.raw}")
            }
        }
    }

    /** Зворотний зв'язок: записати фактичний стан актуаторів у хмару. */
    suspend fun report(report: ActuatorReport): Result<Unit> {
        val target = feedback ?: return Result.failure(IllegalStateException("Firebase не налаштовано"))
        return try {
            target.report(report)
            _state.update { it.copy(feedbackSent = it.feedbackSent + 1, lastFeedbackOk = true) }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            _state.update { it.copy(lastFeedbackOk = false, lastError = message) }
            addLog("зворотний зв'язок не надіслано: $message", isError = true)
            Result.failure(e)
        }
    }

    fun addLog(text: String, isError: Boolean = false) {
        _state.update { it.copy(log = (listOf(ControlLogEntry(clock(), text, isError)) + it.log).take(logSize)) }
    }
}

/** Короткий опис команди для журналу. */
fun ControlCommand.describe(): String =
    "led_status=$ledStatus, alarm=$alarm, auto_enabled=$autoEnabled" +
        (threshold?.let { ", threshold=$it" } ?: "")

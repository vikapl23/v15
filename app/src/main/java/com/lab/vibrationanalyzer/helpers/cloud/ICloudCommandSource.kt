package com.lab.vibrationanalyzer.helpers.cloud

import com.lab.vibrationanalyzer.repository.ActuatorReport
import com.lab.vibrationanalyzer.repository.ControlCommand
import kotlinx.coroutines.flow.Flow

/** ЛР4. Як додаток дізнається про нові команди з хмари. */
enum class ChannelMode(val title: String, val short: String) {
    /** Event-driven через Firebase SDK: ValueEventListener (потрібен google-services.json). */
    FIREBASE_SDK("Event-driven: ValueEventListener (Firebase SDK)", "SDK"),
    /** Event-driven через REST: потік подій Server-Sent Events (досить cloud.url). */
    EVENT_DRIVEN("Event-driven: потік подій REST (SSE)", "SSE"),
    /** Циклічне опитування для порівняння. */
    POLLING("Polling: GET-запит кожні 2 с", "Polling")
}

/** Що приходить з каналу команд. */
sealed interface ControlUpdate {
    /** З'єднання встановлено (SSE) або перший запит успішний (Polling). */
    data object Connected : ControlUpdate

    /** Зв'язок із сервером втрачено, але канал сам перепідключиться (Firebase SDK). */
    data class Disconnected(val reason: String) : ControlUpdate

    /** Виконано один HTTP-запит опитування (лише Polling). */
    data object Polled : ControlUpdate

    /** Нове значення гілки control. raw — JSON події для журналу. */
    data class Command(val command: ControlCommand, val raw: String) : ControlUpdate
}

/**
 * ЛР4. Джерело команд Cloud-to-Device. Flow живе, поки його збирають;
 * при помилці мережі Flow завершується винятком, а перепідключення робить репозиторій.
 */
interface ICloudCommandSource {
    val mode: ChannelMode
    val endpoint: String
    fun updates(): Flow<ControlUpdate>
}

/** ЛР4. Зворотний зв'язок: запис фактичного стану актуаторів у хмару. */
interface IActuatorFeedback {
    val endpoint: String
    suspend fun report(report: ActuatorReport)
}

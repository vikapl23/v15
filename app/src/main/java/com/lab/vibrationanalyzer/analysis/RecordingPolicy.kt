package com.lab.vibrationanalyzer.analysis

import kotlin.math.abs

/** Режим запису в БД (перемикач на екрані «Аналітика»). */
enum class RecordingMode(val title: String) {
    /** Пишемо, коли значення суттєво змінилось (і не частіше за minIntervalMs). */
    ON_CHANGE("Суттєва зміна"),

    /** Пишемо рівно раз на timerIntervalMs, незалежно від значення. */
    TIMER("Таймер")
}

/**
 * ЛР5. Правило «чи писати цей відлік у БД».
 *
 * Сенсор видає ~50 значень за секунду. Писати кожне — це 180 000 рядків за годину,
 * зайве навантаження на флеш-пам'ять і батарею. Тому записуємо лише важливі моменти:
 *
 * Режим ON_CHANGE (за замовчуванням), запис якщо виконується хоча б одне:
 *  1. перший відлік після старту;
 *  2. змінився стан актуатора (actionTriggered) — подію тривоги не можна пропустити;
 *  3. |value − останнє записане| ≥ minDelta (суттєва зміна) і минуло ≥ minIntervalMs;
 *  4. минуло ≥ heartbeatMs від останнього запису («пульс»: навіть у спокої
 *     на графіку є точки і видно, що система жива).
 *
 * Режим TIMER: запис, якщо від останнього минуло ≥ timerIntervalMs
 * (плюс пункт 2 — зміну стану актуатора пишемо одразу).
 *
 * ЛР6: «прискорений режим» (boosted). Коли модель ШІ бачить робочу вібрацію або злом
 * (аналог «Біг» у фітнес-трекері), інтервали скорочуються в 4–5 разів: подію треба записати
 * детально, а в спокої економимо пам'ять. Пороги minDelta не змінюються.
 *
 * Чистий Kotlin без Android, тому повністю перевіряється юніт-тестами.
 */
class RecordingPolicy(
    val minDelta: Float = 0.05f,          // м/с² — менші зміни вважаємо шумом
    val minIntervalMs: Long = 200L,       // не частіше 5 записів/с
    val heartbeatMs: Long = 5_000L,       // у спокої — точка раз на 5 с
    val timerIntervalMs: Long = 1_000L,   // режим «Таймер» — раз на 1 с
    // ЛР6: інтервали в прискореному режимі (модель бачить активність)
    val boostMinIntervalMs: Long = 50L,       // до 20 записів/с
    val boostHeartbeatMs: Long = 1_000L,
    val boostTimerIntervalMs: Long = 200L     // «Таймер» — 5 разів/с
) {
    var mode: RecordingMode = RecordingMode.ON_CHANGE

    /** ЛР6: true — модель ШІ детектує високу активність, пишемо частіше. */
    var boosted: Boolean = false

    /** Інтервали, що діють зараз (звичайні або прискорені). */
    val currentMinIntervalMs: Long get() = if (boosted) boostMinIntervalMs else minIntervalMs
    val currentHeartbeatMs: Long get() = if (boosted) boostHeartbeatMs else heartbeatMs
    val currentTimerIntervalMs: Long get() = if (boosted) boostTimerIntervalMs else timerIntervalMs

    private var lastValue: Float? = null
    private var lastTimeMs: Long = 0L
    private var lastTriggered: Boolean = false

    /** Повертає true, якщо відлік треба зберегти. Якщо так — запам'ятовує його як «останній записаний». */
    fun shouldRecord(value: Float, triggered: Boolean, nowMs: Long): Boolean {
        val previous = lastValue
        val elapsed = nowMs - lastTimeMs
        val record = when {
            previous == null -> true
            triggered != lastTriggered -> true
            mode == RecordingMode.TIMER -> elapsed >= currentTimerIntervalMs
            elapsed >= currentHeartbeatMs -> true
            else -> elapsed >= currentMinIntervalMs && abs(value - previous) >= minDelta
        }
        if (record) {
            lastValue = value
            lastTimeMs = nowMs
            lastTriggered = triggered
        }
        return record
    }

    /** Забути останній запис (наприклад, після очищення БД). */
    fun reset() {
        lastValue = null
        lastTimeMs = 0L
        lastTriggered = false
    }
}

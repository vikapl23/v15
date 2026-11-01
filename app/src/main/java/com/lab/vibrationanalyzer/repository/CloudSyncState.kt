package com.lab.vibrationanalyzer.repository

/** ЛР3. Стан хмарної синхронізації. Від нього залежить колір індикатора. */
enum class SyncStatus(val title: String) {
    IDLE("Очікування першої відправки"),
    SENDING("Відправка…"),
    SYNCED("Синхронізовано"),
    PENDING("Pending: немає інтернету"),
    ERROR("Помилка запиту")
}

/** Один рядок журналу відправок на екрані «Хмара». */
data class CloudRecord(
    val data: SensorData,
    val reason: String,          // «таймер», «зміна Δ», «з черги»…
    val result: Result,
    val remoteId: String? = null,
    val error: String? = null
) {
    enum class Result { SENT, PENDING, FAILED }
}

/** Усе, що репозиторій знає про синхронізацію. */
data class CloudSyncState(
    val backendName: String = "",
    val endpoint: String = "",
    val status: SyncStatus = SyncStatus.IDLE,
    val lastRequestOk: Boolean? = null,  // результат ОСТАННЬОГО справжнього запиту
    val sentCount: Int = 0,
    val failedCount: Int = 0,
    val pendingCount: Int = 0,
    val lastSentAtMs: Long? = null,
    val lastError: String? = null,
    val log: List<CloudRecord> = emptyList()   // найновіші зверху
) {
    /**
     * Колір індикатора «Хмарна синхронізація»:
     * true — зелений (останній запит успішний), false — червоний (помилка або Pending),
     * null — сірий (ще жодного запиту). Під час відправки колір не змінюється,
     * щоб індикатор не блимав кожні 5 секунд.
     */
    val indicatorOk: Boolean?
        get() = when (status) {
            SyncStatus.PENDING, SyncStatus.ERROR -> false
            SyncStatus.SYNCED -> true
            SyncStatus.IDLE, SyncStatus.SENDING -> lastRequestOk
        }
}

/** Що сталося з відліком, переданим у [CloudRepository.offer]. */
sealed interface OfferResult {
    data object Skipped : OfferResult                    // ще не час відправляти
    data class Sent(val remoteId: String) : OfferResult  // успішно, сервер повернув id
    data object Queued : OfferResult                     // немає інтернету → Pending
    data class Failed(val error: String) : OfferResult   // помилка мережі/сервера
}

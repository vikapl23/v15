package com.lab.vibrationanalyzer.repository

import com.lab.vibrationanalyzer.helpers.INetworkMonitor
import com.lab.vibrationanalyzer.helpers.cloud.ICloudUploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ЛР3. Репозиторій хмарної синхронізації: тут уся мережева логіка.
 *
 *  1. [offer] отримує кожен відлік, але відправляє лише коли дозволяє [SyncPolicy]
 *     (раз на 5 с або при зміні на Δ).
 *  2. Якщо інтернету немає — запис іде в чергу, статус стає Pending.
 *  3. Коли мережа повертається, [flushPending] досилає чергу в тому ж порядку.
 *
 * Самі запити робить [ICloudUploader] (Retrofit), а про мережу каже [INetworkMonitor].
 * Обидва — інтерфейси, тому репозиторій тестується на JVM без Android і без інтернету.
 */
class CloudRepository(
    private val uploader: ICloudUploader,
    private val network: INetworkMonitor,
    val policy: SyncPolicy = SyncPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPending: Int = 100,
    private val logSize: Int = 30,
    /** Куди писати журнал. У додатку — Logcat (тег CLOUD_SYNC), у тестах — нікуди. */
    private val logcat: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow(
        CloudSyncState(backendName = uploader.backendName, endpoint = uploader.endpoint)
    )
    val state: StateFlow<CloudSyncState> = _state.asStateFlow()

    /** Черга записів зі статусом Pending (в пам'яті, найстаріші попереду). */
    private val queue = ArrayDeque<SensorData>()

    /** Mutex: одночасно йде лише один запит, порядок записів зберігається. */
    private val mutex = Mutex()

    /** Викликається для кожного нового відліку. Сам вирішує, чи слати. */
    suspend fun offer(data: SensorData): OfferResult = mutex.withLock {
        val now = clock()
        val reason = policy.check(data, now) ?: return@withLock OfferResult.Skipped
        policy.markSent(data, now)

        if (!network.isOnline.value) {
            enqueue(data)
            addLog(CloudRecord(data, reason.title, CloudRecord.Result.PENDING))
            _state.update { it.copy(status = SyncStatus.PENDING, pendingCount = queue.size) }
            logcat("немає мережі → Pending (${queue.size} у черзі)")
            return@withLock OfferResult.Queued
        }

        // Спершу досилаємо старі записи, щоб у хмарі був правильний порядок
        if (queue.isNotEmpty()) flushLocked()
        if (queue.isNotEmpty()) {
            // Черга не пройшла (сервер недоступний) — новий запис ставимо за нею
            enqueue(data)
            addLog(CloudRecord(data, reason.title, CloudRecord.Result.FAILED, error = _state.value.lastError))
            _state.update { it.copy(pendingCount = queue.size) }
            return@withLock OfferResult.Failed(_state.value.lastError ?: "сервер недоступний")
        }
        send(data, reason.title)
    }

    /** Досилає чергу Pending. Повертає кількість успішно надісланих записів. */
    suspend fun flushPending(): Int = mutex.withLock { flushLocked() }

    /** Мережа зникла — одразу показуємо Pending, не чекаючи наступного запиту. */
    fun onNetworkLost() {
        _state.update { it.copy(status = SyncStatus.PENDING) }
    }

    /** Мережа повернулась, а черга порожня — повертаємо статус останнього запиту. */
    private fun restoreStatus() {
        _state.update {
            it.copy(
                status = when (it.lastRequestOk) {
                    true -> SyncStatus.SYNCED
                    false -> SyncStatus.ERROR
                    null -> SyncStatus.IDLE
                }
            )
        }
    }

    private suspend fun flushLocked(): Int {
        var sent = 0
        while (queue.isNotEmpty() && network.isOnline.value) {
            val result = send(queue.first(), "з черги", fromQueue = true)
            if (result !is OfferResult.Sent) break
            sent++
        }
        if (queue.isEmpty() && _state.value.status == SyncStatus.PENDING) restoreStatus()
        return sent
    }

    /** Один POST-запит. Виняток мережі перетворюємо на статус ERROR, а не на падіння. */
    private suspend fun send(data: SensorData, reason: String, fromQueue: Boolean = false): OfferResult {
        _state.update { it.copy(status = SyncStatus.SENDING) }
        return try {
            val id = uploader.upload(data)
            if (fromQueue) queue.removeFirst()
            addLog(CloudRecord(data, reason, CloudRecord.Result.SENT, remoteId = id))
            _state.update {
                it.copy(
                    status = SyncStatus.SYNCED,
                    lastRequestOk = true,
                    sentCount = it.sentCount + 1,
                    pendingCount = queue.size,
                    lastSentAtMs = clock(),
                    lastError = null
                )
            }
            logcat("надіслано: id=$id value=${data.value} ($reason)")
            OfferResult.Sent(id)
        } catch (e: CancellationException) {
            throw e   // скасування корутини не є помилкою мережі
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            if (!fromQueue) enqueue(data)   // не губимо запис: спробуємо пізніше
            if (!fromQueue) addLog(CloudRecord(data, reason, CloudRecord.Result.FAILED, error = message))
            _state.update {
                it.copy(
                    status = SyncStatus.ERROR,
                    lastRequestOk = false,
                    failedCount = it.failedCount + 1,
                    pendingCount = queue.size,
                    lastError = message
                )
            }
            logcat("помилка відправки: $message")
            OfferResult.Failed(message)
        }
    }

    private fun enqueue(data: SensorData) {
        if (queue.size >= maxPending) queue.removeFirst()   // найстаріший запис відкидаємо
        queue.addLast(data)
    }

    private fun addLog(record: CloudRecord) {
        _state.update { it.copy(log = (listOf(record) + it.log).take(logSize)) }
    }
}

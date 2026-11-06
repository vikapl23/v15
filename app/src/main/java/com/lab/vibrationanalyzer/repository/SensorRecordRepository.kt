package com.lab.vibrationanalyzer.repository

import com.lab.vibrationanalyzer.repository.local.RecordStats
import com.lab.vibrationanalyzer.repository.local.SensorRecord
import com.lab.vibrationanalyzer.repository.local.SensorRecordDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * ЛР5. Репозиторій локальної історії — єдиний, хто звертається до DAO.
 *
 * ViewModel не знає ні про Room, ні про SQL: вона кличе save()/observeLast().
 * Якщо колись замінимо Room на щось інше, зміниться лише цей клас.
 *
 * Потоки виконання:
 *  - Запис/видалення — `withContext(ioDispatcher)`: робота з файлом БД іде на
 *    пулі потоків Dispatchers.IO, головний (UI) потік не блокується.
 *    Room і сам кидає IllegalStateException «Cannot access database on the main thread»,
 *    якщо спробувати звернутися до БД з UI-потоку синхронно.
 *  - Читання — Flow з `flowOn(ioDispatcher)`: запит виконується у фоні,
 *    а результат приходить туди, де його збирають (viewModelScope → Main).
 *
 * [ioDispatcher] передається в конструктор, щоб у тестах підставити тестовий диспетчер.
 */
class SensorRecordRepository(
    private val dao: SensorRecordDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /** Зберегти один вимір. Повертає id нового рядка. */
    suspend fun save(value: Float, actionTriggered: Boolean, timestampMs: Long): Long =
        withContext(ioDispatcher) {
            dao.insert(
                SensorRecord(
                    timestamp = timestampMs,
                    sensorValue = value,
                    actionTriggered = actionTriggered
                )
            )
        }

    /** Останні [limit] записів (для графіка), від старих до нових. Оновлюється після кожного запису. */
    fun observeLast(limit: Int): Flow<List<SensorRecord>> =
        dao.observeLast(limit).flowOn(ioDispatcher)

    /** Записи, новіші за [sinceMs] (графік «за останню годину»). */
    fun observeSince(sinceMs: Long): Flow<List<SensorRecord>> =
        dao.observeSince(sinceMs).flowOn(ioDispatcher)

    /** Останні [limit] спрацювань актуатора — журнал системи безпеки. */
    fun observeTriggered(limit: Int): Flow<List<SensorRecord>> =
        dao.observeTriggered(limit).flowOn(ioDispatcher)

    fun observeStats(sinceMs: Long): Flow<RecordStats> =
        dao.observeStats(sinceMs).flowOn(ioDispatcher)

    fun observeCount(): Flow<Int> = dao.observeCount().flowOn(ioDispatcher)

    /** Видалити записи, старші за [retentionMs]. Повертає кількість видалених рядків. */
    suspend fun deleteOlderThan(retentionMs: Long, nowMs: Long = System.currentTimeMillis()): Int =
        withContext(ioDispatcher) { dao.deleteOlderThan(nowMs - retentionMs) }

    suspend fun clear(): Int = withContext(ioDispatcher) { dao.deleteAll() }
}

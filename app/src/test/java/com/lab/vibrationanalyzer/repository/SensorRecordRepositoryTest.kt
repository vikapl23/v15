package com.lab.vibrationanalyzer.repository

import com.lab.vibrationanalyzer.repository.local.RecordStats
import com.lab.vibrationanalyzer.repository.local.SensorRecord
import com.lab.vibrationanalyzer.repository.local.SensorRecordDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * ЛР5. Репозиторій перевіряємо з фальшивим DAO в пам'яті: справжній Room потребує Android
 * (це перевіряється на емуляторі через Database Inspector). Тут важливо, що репозиторій
 * правильно формує SensorRecord і що запити йдуть через переданий диспетчер (IO).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SensorRecordRepositoryTest {

    /** Імітація таблиці: MutableStateFlow, як і Room, повторно віддає дані після кожної зміни. */
    private class FakeDao : SensorRecordDao {
        val rows = MutableStateFlow<List<SensorRecord>>(emptyList())
        var insertThread: String? = null
        private var nextId = 1L

        override suspend fun insert(record: SensorRecord): Long {
            insertThread = Thread.currentThread().name
            val id = nextId++
            rows.value = rows.value + record.copy(id = id)
            return id
        }

        override fun observeLast(limit: Int): Flow<List<SensorRecord>> =
            rows.map { it.sortedBy(SensorRecord::timestamp).takeLast(limit) }

        override suspend fun getLast(limit: Int) =
            rows.value.sortedByDescending(SensorRecord::timestamp).take(limit)

        override fun observeSince(since: Long) =
            rows.map { list -> list.filter { it.timestamp >= since }.sortedBy(SensorRecord::timestamp) }

        override fun observeTriggered(limit: Int) =
            rows.map { list -> list.filter { it.actionTriggered }.sortedByDescending(SensorRecord::timestamp).take(limit) }

        override fun observeStats(since: Long) = rows.map { list ->
            val l = list.filter { it.timestamp >= since }
            RecordStats(
                count = l.size,
                average = if (l.isEmpty()) null else l.map { it.sensorValue }.average().toFloat(),
                maximum = l.maxOfOrNull { it.sensorValue },
                triggeredCount = if (l.isEmpty()) null else l.count { it.actionTriggered }
            )
        }

        override fun observeCount() = rows.map { it.size }

        override suspend fun deleteOlderThan(before: Long): Int {
            val old = rows.value.count { it.timestamp < before }
            rows.value = rows.value.filter { it.timestamp >= before }
            return old
        }

        override suspend fun deleteAll(): Int {
            val n = rows.value.size
            rows.value = emptyList()
            return n
        }
    }

    @Test
    fun saveBuildsEntityAndReturnsId() = runTest {
        val dao = FakeDao()
        val repo = SensorRecordRepository(dao, StandardTestDispatcher(testScheduler))

        val id = repo.save(value = 1.75f, actionTriggered = true, timestampMs = 123L)

        assertEquals(1L, id)
        assertEquals(SensorRecord(id = 1, timestamp = 123L, sensorValue = 1.75f, actionTriggered = true), dao.rows.value.single())
    }

    @Test
    fun observeLastReturnsOnlyNewestInTimeOrder() = runTest {
        val dao = FakeDao()
        val repo = SensorRecordRepository(dao, StandardTestDispatcher(testScheduler))
        repeat(60) { i -> repo.save(i / 10f, false, timestampMs = i * 1_000L) }

        val last = repo.observeLast(50).first()

        assertEquals(50, last.size)
        assertEquals(10_000L, last.first().timestamp)
        assertEquals(59_000L, last.last().timestamp)
    }

    @Test
    fun triggeredHistoryAndStats() = runTest {
        val dao = FakeDao()
        val repo = SensorRecordRepository(dao, StandardTestDispatcher(testScheduler))
        repo.save(0.2f, false, 1_000)
        repo.save(1.8f, true, 2_000)
        repo.save(2.0f, true, 3_000)

        val history = repo.observeTriggered(10).first()
        assertEquals(listOf(3_000L, 2_000L), history.map { it.timestamp })

        val stats = repo.observeStats(sinceMs = 0).first()
        assertEquals(3, stats.count)
        assertEquals(2.0f, stats.maximum!!, 1e-6f)
        assertEquals(2, stats.triggeredCount)
    }

    @Test
    fun deleteOlderThanKeepsRecentRecords() = runTest {
        val dao = FakeDao()
        val repo = SensorRecordRepository(dao, StandardTestDispatcher(testScheduler))
        repo.save(0.1f, false, 1_000)
        repo.save(0.1f, false, 9_000)

        val deleted = repo.deleteOlderThan(retentionMs = 5_000, nowMs = 10_000)

        assertEquals(1, deleted)
        assertEquals(listOf(9_000L), dao.rows.value.map { it.timestamp })
    }

    @Test
    fun writesRunOnInjectedDispatcherNotOnCaller() = runTest {
        val dao = FakeDao()
        // Окремий потік імітує Dispatchers.IO
        val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-io") }
            .asCoroutineDispatcher()
        try {
            SensorRecordRepository(dao, dispatcher).save(0.5f, false, 1L)
            assertTrue(dao.insertThread!!.startsWith("fake-io"))
        } finally {
            dispatcher.close()
        }
    }
}

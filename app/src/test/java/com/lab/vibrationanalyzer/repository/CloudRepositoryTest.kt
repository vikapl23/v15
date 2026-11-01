package com.lab.vibrationanalyzer.repository

import com.lab.vibrationanalyzer.helpers.INetworkMonitor
import com.lab.vibrationanalyzer.helpers.cloud.ICloudUploader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * ЛР3. Репозиторій хмари без Android і без інтернету:
 * мережу і сервер підміняємо фейками.
 */
class CloudRepositoryTest {

    private class FakeNetwork(online: Boolean = true) : INetworkMonitor {
        override val isOnline = MutableStateFlow(online)
    }

    /** «Сервер», що запам'ятовує отримані записи. */
    private class FakeUploader : ICloudUploader {
        override val backendName = "fake"
        override val endpoint = "https://example.test/readings.json"
        val received = mutableListOf<SensorData>()
        var failWith: Exception? = null
        override suspend fun upload(data: SensorData): String {
            failWith?.let { throw it }
            received += data
            return "-id${received.size}"
        }
    }

    private var now = 0L
    private val network = FakeNetwork()
    private val uploader = FakeUploader()
    private val repo = CloudRepository(uploader, network, SyncPolicy(intervalMs = 5_000), clock = { now })

    private fun reading(value: Float, t: Long = now) =
        SensorData("vibro-15", t, SensorData.TYPE_ACCELEROMETER, value, 0f, 9.8f, 0f, "NORMAL", 0f)

    @Test
    fun initialIndicatorIsGrey() {
        assertEquals(SyncStatus.IDLE, repo.state.value.status)
        assertNull(repo.state.value.indicatorOk)
    }

    @Test
    fun sendsOnlyEveryFiveSeconds() = runTest {
        // 50 Гц протягом 12 с = 600 відліків, але в хмару мають піти лише 3 (0 с, 5 с, 10 с)
        for (i in 0 until 600) {
            now = i * 20L
            repo.offer(reading(0.2f))
        }
        assertEquals(3, uploader.received.size)
        assertEquals(listOf(0L, 5_000L, 10_000L), uploader.received.map { it.timestamp })
        assertEquals(SyncStatus.SYNCED, repo.state.value.status)
        assertEquals(true, repo.state.value.indicatorOk)   // зелений
    }

    @Test
    fun offlineReadingGetsPendingAndIsSentLater() = runTest {
        network.isOnline.value = false
        val result = repo.offer(reading(0.3f))
        assertEquals(OfferResult.Queued, result)
        assertEquals(SyncStatus.PENDING, repo.state.value.status)
        assertEquals(false, repo.state.value.indicatorOk)  // червоний
        assertEquals(1, repo.state.value.pendingCount)
        assertTrue(uploader.received.isEmpty())

        now = 5_000
        repo.offer(reading(0.4f))
        assertEquals(2, repo.state.value.pendingCount)

        network.isOnline.value = true
        assertEquals(2, repo.flushPending())
        assertEquals(listOf(0.3f, 0.4f), uploader.received.map { it.value })   // порядок збережено
        assertEquals(0, repo.state.value.pendingCount)
        assertEquals(SyncStatus.SYNCED, repo.state.value.status)
    }

    @Test
    fun serverErrorTurnsIndicatorRedAndKeepsRecord() = runTest {
        uploader.failWith = IOException("HTTP 401 Unauthorized")
        val result = repo.offer(reading(0.5f))
        assertTrue(result is OfferResult.Failed)
        assertEquals(SyncStatus.ERROR, repo.state.value.status)
        assertEquals(false, repo.state.value.indicatorOk)
        assertEquals("HTTP 401 Unauthorized", repo.state.value.lastError)
        assertEquals(1, repo.state.value.pendingCount)

        // Сервер «ожив» — наступна відправка спершу досилає старий запис
        uploader.failWith = null
        now = 5_000
        assertTrue(repo.offer(reading(0.6f)) is OfferResult.Sent)
        assertEquals(listOf(0.5f, 0.6f), uploader.received.map { it.value })
        assertEquals(0, repo.state.value.pendingCount)
        assertEquals(true, repo.state.value.indicatorOk)
    }

    @Test
    fun networkLossShowsPendingImmediately() = runTest {
        repo.offer(reading(0.2f))
        assertEquals(SyncStatus.SYNCED, repo.state.value.status)
        repo.onNetworkLost()
        assertEquals(SyncStatus.PENDING, repo.state.value.status)
        assertFalse(repo.state.value.indicatorOk!!)
        // Мережа повернулась, черга порожня → знову зелений
        assertEquals(0, repo.flushPending())
        assertEquals(SyncStatus.SYNCED, repo.state.value.status)
    }

    @Test
    fun queueIsLimited() = runTest {
        val small = CloudRepository(uploader, network, SyncPolicy(intervalMs = 1), clock = { now }, maxPending = 3)
        network.isOnline.value = false
        for (i in 1..5) {
            now = i * 10L
            small.offer(reading(i.toFloat()))
        }
        assertEquals(3, small.state.value.pendingCount)
        network.isOnline.value = true
        small.flushPending()
        assertEquals(listOf(3f, 4f, 5f), uploader.received.map { it.value })   // найстаріші відкинуто
    }
}

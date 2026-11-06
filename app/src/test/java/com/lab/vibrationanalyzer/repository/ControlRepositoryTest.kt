package com.lab.vibrationanalyzer.repository

import com.lab.vibrationanalyzer.helpers.cloud.ChannelMode
import com.lab.vibrationanalyzer.helpers.cloud.ControlUpdate
import com.lab.vibrationanalyzer.helpers.cloud.IActuatorFeedback
import com.lab.vibrationanalyzer.helpers.cloud.ICloudCommandSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** ЛР4. Репозиторій керування з фейковими каналом і «сервером». */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlRepositoryTest {

    /** Канал, у який тест сам «надсилає» події, як консоль Firebase. */
    private class FakeSource(override val mode: ChannelMode) : ICloudCommandSource {
        override val endpoint = "fake://control.json"
        var channel = Channel<ControlUpdate>(Channel.UNLIMITED)
        var connections = 0
        override fun updates(): Flow<ControlUpdate> {
            connections++
            return channel.receiveAsFlow()
        }
    }

    private class FakeFeedback : IActuatorFeedback {
        override val endpoint = "fake://actuators.json"
        val reports = mutableListOf<ActuatorReport>()
        var fail = false
        override suspend fun report(report: ActuatorReport) {
            if (fail) throw IOException("offline")
            reports += report
        }
    }

    private val stream = FakeSource(ChannelMode.EVENT_DRIVEN)
    private val polling = FakeSource(ChannelMode.POLLING)
    private val feedback = FakeFeedback()
    private val repo = ControlRepository(
        sources = mapOf(ChannelMode.EVENT_DRIVEN to stream, ChannelMode.POLLING to polling),
        feedback = feedback,
        clock = { 1_000L }
    )

    private fun report(torch: Boolean) =
        ActuatorReport(torch, "CLOUD", false, false, "NONE", 0.1f, 1.5f, 0)

    @Test
    fun notConfiguredWithoutSources() {
        val r = ControlRepository(emptyMap(), null)
        assertEquals(ChannelStatus.NOT_CONFIGURED, r.state.value.status)
        assertFalse(r.isConfigured)
    }

    @Test
    fun commandFromStreamReachesState() = runTest {
        val job = launch { repo.listen(ChannelMode.EVENT_DRIVEN) }
        stream.channel.send(ControlUpdate.Connected)
        stream.channel.send(ControlUpdate.Command(ControlCommand(ledStatus = true), """{"path":"/led_status","data":true}"""))
        runCurrent()
        val s = repo.state.value
        assertEquals(ChannelStatus.CONNECTED, s.status)
        assertTrue(s.command.ledStatus)
        assertEquals(1, s.commandsReceived)
        assertEquals(1, s.httpRequests)     // SSE: одне з'єднання = один запит
        job.cancel()
        runCurrent()
        assertEquals(ChannelStatus.STOPPED, repo.state.value.status)
    }

    @Test
    fun pollingCountsEveryRequest() = runTest {
        val job = launch { repo.listen(ChannelMode.POLLING) }
        polling.channel.send(ControlUpdate.Connected)
        repeat(10) { polling.channel.send(ControlUpdate.Polled) }
        polling.channel.send(ControlUpdate.Command(ControlCommand(alarm = true), "{}"))
        runCurrent()
        assertEquals(10, repo.state.value.httpRequests)
        assertEquals(1, repo.state.value.commandsReceived)
        assertEquals(ChannelMode.POLLING, repo.state.value.mode)
        job.cancel()
    }

    @Test
    fun reconnectsAfterFailureWithBackoff() = runTest {
        var attempts = 0
        val flaky = object : ICloudCommandSource {
            override val mode = ChannelMode.EVENT_DRIVEN
            override val endpoint = "fake"
            override fun updates() = flow<ControlUpdate> {
                attempts++
                throw IOException("Unable to resolve host")
            }
        }
        val r = ControlRepository(mapOf(ChannelMode.EVENT_DRIVEN to flaky), null, retryDelaysMs = listOf(1_000, 2_000))
        val job = launch { r.listen(ChannelMode.EVENT_DRIVEN) }
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(ChannelStatus.RECONNECTING, r.state.value.status)
        assertEquals("Unable to resolve host", r.state.value.lastError)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, attempts)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(3, attempts)
        job.cancel()
    }

    @Test
    fun disconnectShowsReconnectingWithoutRestart() = runTest {
        val job = launch { repo.listen(ChannelMode.EVENT_DRIVEN) }
        stream.channel.send(ControlUpdate.Connected)
        stream.channel.send(ControlUpdate.Disconnected("немає з'єднання"))
        runCurrent()
        assertEquals(ChannelStatus.RECONNECTING, repo.state.value.status)
        stream.channel.send(ControlUpdate.Connected)
        runCurrent()
        assertEquals(ChannelStatus.CONNECTED, repo.state.value.status)
        assertEquals(1, stream.connections)     // канал не перезапускали
        job.cancel()
    }

    @Test
    fun availableModesFollowSources() {
        assertEquals(listOf(ChannelMode.EVENT_DRIVEN, ChannelMode.POLLING), repo.availableModes)
        assertEquals(ChannelMode.EVENT_DRIVEN, repo.state.value.mode)
    }

    @Test
    fun feedbackSuccessAndFailure() = runTest {
        assertTrue(repo.report(report(true)).isSuccess)
        assertEquals(1, feedback.reports.size)
        assertEquals(true, repo.state.value.lastFeedbackOk)
        feedback.fail = true
        assertTrue(repo.report(report(false)).isFailure)
        assertEquals(false, repo.state.value.lastFeedbackOk)
        assertEquals(1, repo.state.value.feedbackSent)
        assertTrue(repo.state.value.log.first().isError)
    }

    @Test
    fun logKeepsNewestFirstAndLimited() {
        val r = ControlRepository(emptyMap(), null, logSize = 3)
        repeat(5) { r.addLog("запис $it") }
        assertEquals(listOf("запис 4", "запис 3", "запис 2"), r.state.value.log.map { it.text })
    }
}

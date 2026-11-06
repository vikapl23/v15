package com.lab.vibrationanalyzer.helpers.cloud

import com.google.gson.JsonParser
import com.lab.vibrationanalyzer.repository.ActuatorReport
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ЛР4. Канал керування проти локального «Firebase» (MockWebServer):
 * перевіряємо заголовок потоку, розбір SSE-подій, опитування і PUT зворотного зв'язку.
 */
class FirebaseControlChannelTest {

    private lateinit var server: MockWebServer
    private lateinit var channel: FirebaseControlChannel

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Використовуємо конструктор CloudConfig напряму: MockWebServer має адресу localhost,
        // а режим FIREBASE визначається за доменом firebasedatabase.app
        val config = CloudConfig(
            mode = CloudConfig.Mode.FIREBASE,
            baseUrl = server.url("/").toString(),
            path = "devices/vibro-15/readings.json",
            auth = "secret",
            deviceId = "vibro-15"
        )
        channel = FirebaseControlChannel(config, pollIntervalMs = 10)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun streamDeliversPutEvents() = runBlocking {
        val sse = "event: put\n" +
            "data: {\"path\":\"/\",\"data\":{\"led_status\":false}}\n\n" +
            "event: keep-alive\ndata: null\n\n" +
            "event: put\n" +
            "data: {\"path\":\"/led_status\",\"data\":true}\n\n"
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse)
        )

        val commands = withTimeout(5_000) {
            channel.eventSource.updates()
                .filterIsInstance<ControlUpdate.Command>()
                .take(2)
                .toList()
        }
        assertFalse(commands[0].command.ledStatus)
        assertTrue(commands[1].command.ledStatus)   // «миттєва реакція» на зміну в консолі

        val request = server.takeRequest()
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertEquals("/devices/vibro-15/control.json?auth=secret", request.path)
    }

    @Test
    fun streamCancelEventEndsFlowWithError() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: cancel\ndata: \"Permission denied\"\n\n")
        )
        val error = runCatching {
            withTimeout(5_000) { channel.eventSource.updates().toList() }
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Permission denied"))
    }

    @Test
    fun pollingEmitsOnlyChanges() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"alarm":false}"""))
        server.enqueue(MockResponse().setBody("""{"alarm":false}"""))
        server.enqueue(MockResponse().setBody("""{"alarm":true}"""))
        val updates = withTimeout(5_000) {
            channel.pollingSource.updates().take(6).toList()
        }
        // Connected, Polled, Command(false), Polled, Polled, Command(true)
        assertEquals(ControlUpdate.Connected, updates[0])
        val commands = updates.filterIsInstance<ControlUpdate.Command>()
        assertEquals(2, commands.size)               // 3 запити, але лише 2 зміни
        assertTrue(commands[1].command.alarm)
        assertEquals(3, updates.count { it == ControlUpdate.Polled })
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun feedbackIsPutAsJson() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        channel.feedback.report(ActuatorReport(true, "CLOUD", false, false, "NONE", 0.12f, 1.5f, 42L))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/devices/vibro-15/actuators.json?auth=secret", request.path)
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertTrue(json["torch"].asBoolean)
        assertEquals("CLOUD", json["torchSource"].asString)
        assertEquals(42L, json["timestamp"].asLong)
    }

    @Test
    fun feedbackHttpErrorThrows() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Permission denied"}"""))
        val error = runCatching {
            channel.feedback.report(ActuatorReport(false, "NONE", false, false, "NONE", 0f, 1.5f, 0))
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().startsWith("HTTP 401"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun requiresFirebaseMode() {
        FirebaseControlChannel(CloudConfig.from("", "", "vibro-15"))
    }
}

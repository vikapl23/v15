package com.lab.vibrationanalyzer.helpers.cloud

import com.lab.vibrationanalyzer.repository.SensorData
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * ЛР3. Справжній Retrofit + OkHttp, але замість Firebase — локальний MockWebServer.
 * Перевіряємо, що йде саме POST з JSON на правильний шлях.
 */
class RetrofitCloudUploaderTest {

    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private val data = SensorData("vibro-15", 1_761_999_000_000, "accelerometer", 0.42f, 0.13f, 9.71f, 1.02f, "ELEVATED", 3.5f)

    private fun uploader(auth: String = ""): RetrofitCloudUploader {
        val base = server.url("/").toString()
        val config = CloudConfig(CloudConfig.Mode.FIREBASE, base, "devices/vibro-15/readings.json", auth, "vibro-15")
        return RetrofitCloudUploader(config)
    }

    @Test
    fun postsJsonAndReturnsFirebaseKey() = runTest {
        server.enqueue(MockResponse().setBody("""{"name":"-NxTestKey"}"""))
        val id = uploader().upload(data)
        assertEquals("-NxTestKey", id)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/devices/vibro-15/readings.json", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"deviceId\":\"vibro-15\""))
        assertTrue(body, body.contains("\"value\":0.42"))
        assertTrue(body, body.contains("\"y\":9.71"))
        assertTrue(body, body.contains("\"type\":\"accelerometer\""))
    }

    @Test
    fun authKeyGoesToQuery() = runTest {
        server.enqueue(MockResponse().setBody("""{"name":"-Nx2"}"""))
        uploader(auth = "secret").upload(data)
        assertEquals("/devices/vibro-15/readings.json?auth=secret", server.takeRequest().path)
    }

    @Test
    fun httpErrorThrows() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Permission denied"}"""))
        try {
            uploader().upload(data)
            fail("мав бути виняток")
        } catch (e: IOException) {
            assertTrue(e.message!!.startsWith("HTTP 401"))
        }
    }
}

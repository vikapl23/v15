package com.lab.vibrationanalyzer.helpers.cloud

import android.util.Log
import com.google.gson.Gson
import com.lab.vibrationanalyzer.repository.ActuatorReport
import com.lab.vibrationanalyzer.repository.ControlCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ЛР4. Канал керування через Firebase Realtime Database (REST API, без google-services.json —
 * так само, як відправка даних у ЛР3).
 *
 * Створює три об'єкти для однієї бази:
 *  - [eventSource]  — Event-driven: потік подій (Server-Sent Events). Аналог ValueEventListener
 *                     з Firebase SDK: сервер САМ надсилає зміну, щойно її зробили в консолі.
 *  - [pollingSource] — Polling: додаток сам питає сервер GET-запитом кожні N секунд.
 *  - [feedback]     — PUT стану актуаторів назад у хмару (зворотний зв'язок).
 */
class FirebaseControlChannel(
    private val config: CloudConfig,
    pollIntervalMs: Long = 2_000L,
    baseClient: OkHttpClient = OkHttpClient()
) {
    init {
        require(config.mode == CloudConfig.Mode.FIREBASE) { "Керування з хмари потребує Firebase" }
    }

    val controlPath = "devices/${config.deviceId}/control.json"
    val feedbackPath = "devices/${config.deviceId}/actuators.json"

    /** Звичайні запити (GET/PUT) — з журналом у Logcat, як у ЛР3. */
    private val restClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor { Log.d(TAG_HTTP, it.replace(AUTH_REGEX, "auth=***")) }
                .apply { level = HttpLoggingInterceptor.Level.BODY }
        )
        .build()

    /**
     * Потік SSE: з'єднання тримається відкритим. Firebase шле keep-alive кожні ~30 с,
     * тому readTimeout більший за цей інтервал. Тіло НЕ логуємо (BODY чекав би кінця потоку).
     */
    private val streamClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val api: ControlApi = Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(restClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ControlApi::class.java)

    val eventSource: ICloudCommandSource = StreamSource()
    val pollingSource: ICloudCommandSource = PollingSource(pollIntervalMs)
    val feedback: IActuatorFeedback = Feedback()

    // ---------- Event-driven: Firebase REST streaming (text/event-stream) ----------

    private inner class StreamSource : ICloudCommandSource {
        override val mode = ChannelMode.EVENT_DRIVEN
        override val endpoint: String get() = "GET (stream) " + config.baseUrl + controlPath

        override fun updates(): Flow<ControlUpdate> = callbackFlow {
            val tree = FirebaseNodeTree()
            val url = (config.baseUrl + controlPath).toHttpUrl().newBuilder().apply {
                config.queryParams.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")   // саме цей заголовок вмикає потік у Firebase
                .build()

            val listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    Log.d(TAG_STREAM, "потік відкрито: HTTP ${response.code}")
                    trySend(ControlUpdate.Connected)
                }

                // Подія від сервера — тут додаток «миттєво реагує» (аналог onDataChange)
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    Log.d(TAG_STREAM, "event: $type data: $data")
                    when (type) {
                        "put", "patch" -> {
                            try {
                                tree.apply(type, data)
                                trySend(ControlUpdate.Command(tree.command(), data))
                            } catch (e: RuntimeException) {
                                Log.e(TAG_STREAM, "некоректна подія: $data", e)
                            }
                        }
                        "keep-alive" -> Unit
                        // Firebase закрив доступ (правила бази змінились або ключ відкликано)
                        "cancel", "auth_revoked" -> close(IOException("Firebase: $type ${data.trim('"')}"))
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    close(IOException("сервер закрив потік"))
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    val reason = t?.message ?: response?.let { "HTTP ${it.code} ${it.message}".trim() }
                    close(IOException(reason ?: "помилка потоку"))
                }
            }

            val source = EventSources.createFactory(streamClient).newEventSource(request, listener)
            awaitClose {
                source.cancel()   // корутину скасовано → закриваємо з'єднання
                Log.d(TAG_STREAM, "потік закрито")
            }
        }
    }

    // ---------- Polling: циклічне опитування ----------

    private inner class PollingSource(private val intervalMs: Long) : ICloudCommandSource {
        override val mode = ChannelMode.POLLING
        override val endpoint: String get() = "GET " + config.baseUrl + controlPath

        override fun updates(): Flow<ControlUpdate> = flow {
            var last: ControlCommand? = null
            val gson = Gson()
            while (true) {
                val response = api.getControl(controlPath, config.queryParams)
                if (!response.isSuccessful) throw IOException("HTTP ${response.code()} ${response.message()}".trim())
                if (last == null) emit(ControlUpdate.Connected)
                emit(ControlUpdate.Polled)
                val body = response.body()
                val command = body.toControlCommand()
                if (command != last) {           // нове значення → передаємо далі
                    last = command
                    emit(ControlUpdate.Command(command, gson.toJson(body)))
                }
                delay(intervalMs)               // а більшість запитів «порожні»: нічого не змінилось
            }
        }.flowOn(Dispatchers.IO)
    }

    // ---------- Зворотний зв'язок ----------

    private inner class Feedback : IActuatorFeedback {
        override val endpoint: String get() = "PUT " + config.baseUrl + feedbackPath

        override suspend fun report(report: ActuatorReport) = withContext(Dispatchers.IO) {
            val response = api.putActuators(feedbackPath, config.queryParams, report)
            if (!response.isSuccessful) throw IOException("HTTP ${response.code()} ${response.message()}".trim())
        }
    }

    private companion object {
        const val TAG_HTTP = "CONTROL_HTTP"
        const val TAG_STREAM = "C2D_STREAM"
        val AUTH_REGEX = Regex("auth=[^&\\s]+")
    }
}

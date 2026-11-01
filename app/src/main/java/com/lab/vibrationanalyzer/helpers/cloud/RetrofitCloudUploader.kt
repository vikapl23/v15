package com.lab.vibrationanalyzer.helpers.cloud

import android.util.Log
import com.google.gson.JsonElement
import com.lab.vibrationanalyzer.repository.SensorData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ЛР3. Відправка даних у хмару через Retrofit 2 + Gson.
 *
 * OkHttp — HTTP-клієнт, на якому працює Retrofit. Саме його запити видно
 * в Android Studio → App Inspection → Network Inspector.
 * HttpLoggingInterceptor дублює кожен запит і відповідь у Logcat (тег CLOUD_HTTP).
 */
class RetrofitCloudUploader(private val config: CloudConfig) : ICloudUploader {

    override val backendName: String get() = config.mode.title
    override val endpoint: String get() = config.endpoint

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor { message ->
                Log.d(TAG, message.replace(AUTH_REGEX, "auth=***"))   // ключ не потрапить у Logcat
            }.apply { level = HttpLoggingInterceptor.Level.BODY }
        )
        .build()

    private val api: CloudApi = Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(CloudApi::class.java)

    override suspend fun upload(data: SensorData): String =
        // Явно переходимо на фоновий пул потоків для мережі (Dispatchers.IO)
        withContext(Dispatchers.IO) {
            val response = api.postReading(config.path, config.queryParams, data)
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code()} ${response.message()}".trim())
            }
            remoteId(response.body())
        }

    /** Дістає id запису з відповіді сервера. */
    private fun remoteId(body: JsonElement?): String {
        val obj = body?.takeIf { it.isJsonObject }?.asJsonObject ?: return "—"
        return when {
            obj.has("name") -> obj.get("name").asString   // Firebase
            obj.has("id") -> obj.get("id").toString()      // JSONPlaceholder / інші REST
            else -> "—"
        }
    }

    private companion object {
        const val TAG = "CLOUD_HTTP"
        val AUTH_REGEX = Regex("auth=[^&\\s]+")
    }
}

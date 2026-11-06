package com.lab.vibrationanalyzer

import android.app.Application
import android.util.Log
import com.lab.vibrationanalyzer.helpers.NetworkMonitor
import com.lab.vibrationanalyzer.helpers.cloud.ChannelMode
import com.lab.vibrationanalyzer.helpers.cloud.CloudConfig
import com.lab.vibrationanalyzer.helpers.cloud.FirebaseControlChannel
import com.lab.vibrationanalyzer.helpers.cloud.FirebaseSdkCommandSource
import com.lab.vibrationanalyzer.helpers.cloud.FirebaseSdkOptions
import com.lab.vibrationanalyzer.helpers.cloud.ICloudCommandSource
import com.lab.vibrationanalyzer.helpers.cloud.RetrofitCloudUploader
import com.lab.vibrationanalyzer.repository.CloudRepository
import com.lab.vibrationanalyzer.repository.ControlRepository
import com.lab.vibrationanalyzer.repository.VibrationRepository

/**
 * Клас Application живе весь час, поки живе процес додатка.
 * Тут створюємо репозиторії в одному екземплярі, щоб усі екрани
 * (Монітор, Смарт-хаб, Хмара, Керування) бачили ті самі дані.
 */
class SmartVibrationApp : Application() {

    val repository: VibrationRepository by lazy { VibrationRepository() }

    // ---------- ЛР3: хмара ----------

    /** Адреса хмари з local.properties (через BuildConfig), а не з коду. */
    val cloudConfig: CloudConfig by lazy {
        try {
            CloudConfig.from(BuildConfig.CLOUD_URL, BuildConfig.CLOUD_AUTH, BuildConfig.DEVICE_ID)
        } catch (e: IllegalArgumentException) {
            Log.e("CLOUD_SYNC", "Некоректний cloud.url у local.properties: ${e.message}")
            CloudConfig.from("", "", BuildConfig.DEVICE_ID)   // запасний варіант — тестовий REST API
        }
    }

    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(this) }

    val cloudRepository: CloudRepository by lazy {
        CloudRepository(
            uploader = RetrofitCloudUploader(cloudConfig),
            network = networkMonitor,
            logcat = { Log.d("CLOUD_SYNC", it) }
        )
    }

    // ---------- ЛР4: дистанційне керування (Cloud-to-Device) ----------

    /** Ключі Firebase SDK з google-services.json (gradle кладе їх у BuildConfig). */
    private val firebaseSdkOptions: FirebaseSdkOptions by lazy {
        FirebaseSdkOptions(
            applicationId = BuildConfig.FIREBASE_APP_ID,
            apiKey = BuildConfig.FIREBASE_API_KEY,
            projectId = BuildConfig.FIREBASE_PROJECT_ID,
            // адреса бази: з local.properties (як у ЛР3) або з google-services.json
            databaseUrl = if (cloudConfig.mode == CloudConfig.Mode.FIREBASE) cloudConfig.baseUrl
            else BuildConfig.FIREBASE_DB_URL
        )
    }

    /**
     * Канал команд працює лише з Firebase: тестовий REST API (JSONPlaceholder) нічого не зберігає,
     * тож і команду з нього отримати не можна. Без Firebase репозиторій має стан NOT_CONFIGURED.
     *
     * Режими (перший доступний — за замовчуванням):
     *  1. SDK  — ValueEventListener, якщо в app/ лежить google-services.json;
     *  2. SSE  — потік подій REST API, якщо в local.properties є cloud.url Firebase;
     *  3. Polling — те саме REST API, але опитуванням (для порівняння у висновках).
     */
    val controlRepository: ControlRepository by lazy {
        val sources = linkedMapOf<ChannelMode, ICloudCommandSource>()
        if (firebaseSdkOptions.isComplete) {
            sources[ChannelMode.FIREBASE_SDK] =
                FirebaseSdkCommandSource(this, firebaseSdkOptions, cloudConfig.deviceId)
        }
        val restConfig = when {
            cloudConfig.mode == CloudConfig.Mode.FIREBASE -> cloudConfig
            firebaseSdkOptions.databaseUrl.isNotBlank() -> runCatching {
                CloudConfig.from(firebaseSdkOptions.databaseUrl, BuildConfig.CLOUD_AUTH, BuildConfig.DEVICE_ID)
            }.getOrNull()?.takeIf { it.mode == CloudConfig.Mode.FIREBASE }
            else -> null
        }
        val channel = restConfig?.let { FirebaseControlChannel(it) }
        if (channel != null) {
            sources[ChannelMode.EVENT_DRIVEN] = channel.eventSource
            sources[ChannelMode.POLLING] = channel.pollingSource
        }
        ControlRepository(
            sources = sources,
            feedback = channel?.feedback,
            logcat = { Log.d("C2D", it) }
        )
    }
}

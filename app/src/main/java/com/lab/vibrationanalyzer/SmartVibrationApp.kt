package com.lab.vibrationanalyzer

import android.app.Application
import android.util.Log
import com.lab.vibrationanalyzer.helpers.NetworkMonitor
import com.lab.vibrationanalyzer.helpers.cloud.CloudConfig
import com.lab.vibrationanalyzer.helpers.cloud.RetrofitCloudUploader
import com.lab.vibrationanalyzer.repository.CloudRepository
import com.lab.vibrationanalyzer.repository.VibrationRepository

/**
 * Клас Application живе весь час, поки живе процес додатка.
 * Тут створюємо репозиторії в одному екземплярі, щоб усі екрани
 * (Монітор, Смарт-хаб, Хмара) бачили ті самі дані.
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
}

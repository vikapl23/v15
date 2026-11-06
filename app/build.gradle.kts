import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)   // ЛР5: KSP генерує код Room (реалізації DAO і бази) під час збірки
}

/*
 * Налаштування хмари читаємо з local.properties (файл НЕ потрапляє в git і zip).
 * Так URL бази і ключ не "зашиті" в код. Приклад рядків у local.properties:
 *   cloud.url=https://smart-vibro-15-default-rtdb.europe-west1.firebasedatabase.app/
 *   cloud.auth=
 *   cloud.device_id=vibro-15
 * Якщо cloud.url порожній — додаток шле дані на тестовий REST API JSONPlaceholder.
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(name: String, default: String = ""): String =
    (localProps.getProperty(name) ?: default).trim().replace("\\", "\\\\").replace("\"", "\\\"")

/*
 * ЛР4. Ключі Firebase SDK (для ValueEventListener) беремо з app/google-services.json,
 * який дає консоль Firebase. Плагін google-services НЕ підключаємо: якщо файлу немає,
 * проєкт однаково збирається, просто режим «SDK» на екрані «Керування» буде недоступний.
 */
val googleServices: Map<String, String> = run {
    val f = file("google-services.json")
    if (!f.exists()) return@run emptyMap()
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
    val project = json["project_info"] as? Map<String, Any?> ?: emptyMap()
    val clients = (json["client"] as? List<Map<String, Any?>>).orEmpty()
    // Беремо клієнта саме нашого пакета (у файлі їх може бути кілька)
    val client = clients.firstOrNull {
        val info = it["client_info"] as? Map<String, Any?>
        val android = info?.get("android_client_info") as? Map<String, Any?>
        android?.get("package_name") == "com.lab.vibrationanalyzer"
    } ?: clients.firstOrNull()
    val clientInfo = client?.get("client_info") as? Map<String, Any?>
    val apiKey = (client?.get("api_key") as? List<Map<String, Any?>>)?.firstOrNull()?.get("current_key")
    mapOf(
        "appId" to (clientInfo?.get("mobilesdk_app_id") ?: "").toString(),
        "apiKey" to (apiKey ?: "").toString(),
        "projectId" to (project["project_id"] ?: "").toString(),
        "dbUrl" to (project["firebase_url"] ?: "").toString()
    )
}
fun gs(name: String): String = googleServices[name].orEmpty().replace("\"", "\\\"")

android {
    namespace = "com.lab.vibrationanalyzer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lab.vibrationanalyzer"
        minSdk = 31          // Android 12.0+
        targetSdk = 35
        versionCode = 6
        versionName = "6.0"

        // Значення з local.properties → поля класу BuildConfig
        buildConfigField("String", "CLOUD_URL", "\"${localProp("cloud.url")}\"")
        buildConfigField("String", "CLOUD_AUTH", "\"${localProp("cloud.auth")}\"")
        buildConfigField("String", "DEVICE_ID", "\"${localProp("cloud.device_id", "vibro-15")}\"")

        // ЛР4: Firebase SDK (з google-services.json, якщо він є)
        buildConfigField("String", "FIREBASE_APP_ID", "\"${gs("appId")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${gs("apiKey")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${gs("projectId")}\"")
        buildConfigField("String", "FIREBASE_DB_URL", "\"${gs("dbUrl")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        // android.util.Log у JVM-тестах повертає 0 замість винятку "not mocked"
        unitTests.isReturnDefaultValues = true
    }
    // ЛР6: .tflite у APK НЕ стискаємо — інакше Interpreter не зможе відобразити модель
    // у пам'ять (AssetManager.openFd кидає FileNotFoundException для стиснутих файлів)
    androidResources {
        noCompress += "tflite"
    }
    buildFeatures {
        compose = true
        buildConfig = true   // потрібно для BuildConfig.CLOUD_URL
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // ЛР3: мережа — Retrofit 2 + Gson (JSON) + журнал HTTP-запитів у Logcat
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging)
    // ЛР4: потік подій Firebase (Server-Sent Events) для Cloud-to-Device керування
    implementation(libs.okhttp.sse)
    // ЛР4: Firebase Realtime Database SDK — ValueEventListener на гілку control
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.database)

    // ЛР5: Room — локальна БД SQLite через анотації (@Entity, @Dao, @Database)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)          // suspend-функції і Flow у DAO
    ksp(libs.room.compiler)                // генерує SensorRecordDao_Impl і AppDatabase_Impl
    // ЛР5: Vico — бібліотека графіків для Jetpack Compose (LineChart)
    implementation(libs.vico.compose.m3)

    // ЛР6: TensorFlow Lite — інтерпретатор моделей .tflite на пристрої (On-device AI)
    implementation(libs.tensorflow.lite)
    // ЛР6: Support Library — FileUtil.loadLabels() і TensorBuffer для вихідного тензора
    implementation(libs.tensorflow.lite.support)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)   // ЛР3: локальний «сервер» для тестів Retrofit
}

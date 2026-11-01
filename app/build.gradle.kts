import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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

android {
    namespace = "com.lab.vibrationanalyzer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lab.vibrationanalyzer"
        minSdk = 31          // Android 12.0+
        targetSdk = 35
        versionCode = 3
        versionName = "3.0"

        // Значення з local.properties → поля класу BuildConfig
        buildConfigField("String", "CLOUD_URL", "\"${localProp("cloud.url")}\"")
        buildConfigField("String", "CLOUD_AUTH", "\"${localProp("cloud.auth")}\"")
        buildConfigField("String", "DEVICE_ID", "\"${localProp("cloud.device_id", "vibro-15")}\"")
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)   // ЛР3: локальний «сервер» для тестів Retrofit
}

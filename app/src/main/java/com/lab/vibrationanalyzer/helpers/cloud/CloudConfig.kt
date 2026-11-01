package com.lab.vibrationanalyzer.helpers.cloud

import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * ЛР3. Куди відправляти дані. Будується з рядків local.properties (через BuildConfig).
 *
 * Три режими:
 *  - FIREBASE: cloud.url — адреса Realtime Database. Запис іде POST-запитом
 *    на <url>/devices/<deviceId>/readings.json (REST API Firebase),
 *    Firebase сам створює унікальний ключ і повертає його як {"name": "-N..."}.
 *  - CUSTOM_REST: будь-яка інша адреса (Mocky, свій сервер) — POST саме на неї.
 *  - MOCK_REST: cloud.url порожній — тестовий публічний API JSONPlaceholder (/posts).
 */
data class CloudConfig(
    val mode: Mode,
    val baseUrl: String,   // для Retrofit: схема + хост + "/"
    val path: String,      // шлях запиту відносно baseUrl
    val auth: String,      // ключ доступу (необов'язковий), додається як ?auth=
    val deviceId: String
) {
    enum class Mode(val title: String) {
        FIREBASE("Firebase Realtime Database (REST)"),
        CUSTOM_REST("REST API"),
        MOCK_REST("JSONPlaceholder (тестовий REST API)")
    }

    /** Адреса для показу на екрані, без ключа. */
    val endpoint: String get() = baseUrl + path

    /** Параметри запиту (?auth=...), якщо ключ задано. */
    val queryParams: Map<String, String> get() = if (auth.isBlank()) emptyMap() else mapOf("auth" to auth)

    companion object {
        const val MOCK_URL = "https://jsonplaceholder.typicode.com/posts"

        fun from(url: String, auth: String, deviceId: String): CloudConfig {
            val id = deviceId.ifBlank { "vibro-15" }
            val raw = url.trim().ifBlank { MOCK_URL }
            val http = raw.toHttpUrl()   // кине виняток, якщо адреса некоректна
            val base = "${http.scheme}://${http.host}" +
                (if (http.port != defaultPort(http.scheme)) ":${http.port}" else "") + "/"
            val existingPath = http.encodedPath.trim('/')

            val isFirebase = http.host.endsWith("firebaseio.com") ||
                http.host.endsWith("firebasedatabase.app")
            return when {
                isFirebase -> CloudConfig(
                    mode = Mode.FIREBASE,
                    baseUrl = base,
                    path = "devices/$id/readings.json",
                    auth = auth.trim(),
                    deviceId = id
                )
                raw == MOCK_URL -> CloudConfig(Mode.MOCK_REST, base, existingPath, auth.trim(), id)
                else -> CloudConfig(Mode.CUSTOM_REST, base, existingPath, auth.trim(), id)
            }
        }

        private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80
    }
}

package com.lab.vibrationanalyzer.helpers.cloud

import com.google.gson.JsonElement
import com.lab.vibrationanalyzer.repository.SensorData
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.QueryMap
import retrofit2.http.Url

/**
 * ЛР3. Опис REST API для Retrofit. Retrofit сам генерує реалізацію цього інтерфейсу:
 * перетворює SensorData на JSON (Gson), відправляє POST і розбирає відповідь.
 *
 * suspend — запит виконується в корутині і не блокує UI-потік.
 */
interface CloudApi {

    /**
     * POST <baseUrl>/<path>?auth=...
     * Тіло — JSON із SensorData. Відповідь — довільний JSON:
     * Firebase повертає {"name": "-NxA..."}, JSONPlaceholder — {"id": 101, ...}.
     */
    @POST
    suspend fun postReading(
        @Url path: String,
        @QueryMap query: Map<String, String>,
        @Body data: SensorData
    ): Response<JsonElement>
}

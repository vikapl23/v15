package com.lab.vibrationanalyzer.helpers.cloud

import com.google.gson.JsonElement
import com.lab.vibrationanalyzer.repository.ActuatorReport
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.QueryMap
import retrofit2.http.Url

/**
 * ЛР4. REST API Firebase для керування (Retrofit генерує реалізацію).
 *  - GET  devices/<id>/control.json   — прочитати команди (режим Polling);
 *  - PUT  devices/<id>/actuators.json — записати зворотний зв'язок (стан актуаторів).
 * Потік подій (Event-driven) іде не через Retrofit, а через OkHttp SSE — див. FirebaseStreamCommandSource.
 */
interface ControlApi {

    @GET
    suspend fun getControl(
        @Url path: String,
        @QueryMap query: Map<String, String>
    ): Response<JsonElement>

    @PUT
    suspend fun putActuators(
        @Url path: String,
        @QueryMap query: Map<String, String>,
        @Body report: ActuatorReport
    ): Response<JsonElement>
}

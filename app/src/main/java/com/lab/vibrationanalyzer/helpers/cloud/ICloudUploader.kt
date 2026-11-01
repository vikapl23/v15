package com.lab.vibrationanalyzer.helpers.cloud

import com.lab.vibrationanalyzer.repository.SensorData

/**
 * ЛР3. Інтерфейс «відправника в хмару». Репозиторій знає лише його,
 * тому в тестах замість справжньої мережі підставляється фейк.
 */
interface ICloudUploader {
    /** Назва хмари для екрана, наприклад «Firebase Realtime Database». */
    val backendName: String

    /** Куди саме йдуть дані (адреса без ключа). */
    val endpoint: String

    /**
     * Відправити один запис. Повертає id, який присвоїв сервер
     * (у Firebase — ключ на кшталт -NxA1b2..., у JSONPlaceholder — число).
     * При помилці мережі або HTTP-коді 4xx/5xx кидає виняток.
     */
    suspend fun upload(data: SensorData): String
}

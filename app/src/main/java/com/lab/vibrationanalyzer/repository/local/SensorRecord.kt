package com.lab.vibrationanalyzer.repository.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ЛР5. Entity — один рядок таблиці `sensor_records` у локальній БД SQLite.
 *
 * Room за цим класом сам створює таблицю:
 * ```
 * CREATE TABLE sensor_records (
 *     id              INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
 *     timestamp       INTEGER NOT NULL,   -- час виміру, мс від 01.01.1970 (System.currentTimeMillis)
 *     sensorValue     REAL    NOT NULL,   -- RMS вібрації, м/с² (значення сенсора з ЛР1)
 *     actionTriggered INTEGER NOT NULL    -- 1, якщо в цей момент працював актуатор (ЛР4), інакше 0
 * )
 * CREATE INDEX index_sensor_records_timestamp ON sensor_records(timestamp)
 * ```
 * Індекс по `timestamp` пришвидшує запити «останні N записів» і «за останню годину»,
 * бо обидва сортують і фільтрують саме за часом.
 */
@Entity(
    tableName = SensorRecord.TABLE,
    indices = [Index(value = ["timestamp"])]
)
data class SensorRecord(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,                     // 0 → SQLite сам видасть наступний номер

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,                  // мс

    @ColumnInfo(name = "sensorValue")
    val sensorValue: Float,               // м/с²

    @ColumnInfo(name = "actionTriggered")
    val actionTriggered: Boolean          // Boolean Room зберігає як INTEGER 0/1
) {
    companion object {
        const val TABLE = "sensor_records"
    }
}

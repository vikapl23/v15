package com.lab.vibrationanalyzer.repository.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * ЛР5. DAO (Data Access Object) — усі SQL-запити до таблиці `sensor_records`.
 *
 * Ми пишемо лише інтерфейс з анотаціями. Реалізацію `SensorRecordDao_Impl`
 * генерує Room (через KSP) під час збірки і заодно перевіряє SQL: помилка
 * в назві колонки чи таблиці — це помилка компіляції, а не падіння додатка.
 *
 * Два види методів:
 *  - `suspend fun` — разова операція (запис, видалення). Викликаємо з корутини;
 *    Room сам переносить її на фоновий потік, а репозиторій ще й явно
 *    обгортає у withContext(Dispatchers.IO).
 *  - `fun …: Flow<…>` — «живий» запит. Room повторює його щоразу, коли таблиця
 *    змінюється (після кожного insert), тому графік оновлюється сам.
 */
@Dao
interface SensorRecordDao {

    /** Записати один вимір. Повертає id нового рядка. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SensorRecord): Long

    /**
     * Останні [limit] записів для графіка, у порядку зростання часу (зліва старі, справа нові).
     * Вкладений SELECT: спершу беремо N найновіших (DESC + LIMIT), потім розвертаємо їх (ASC).
     */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM sensor_records ORDER BY timestamp DESC, id DESC LIMIT :limit
        ) ORDER BY timestamp ASC, id ASC
        """
    )
    fun observeLast(limit: Int): Flow<List<SensorRecord>>

    /** Те саме, але разово (без підписки) — для тестів і експорту. */
    @Query("SELECT * FROM sensor_records ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getLast(limit: Int): List<SensorRecord>

    /** Усі записи, новіші за [since] (мс), — «графік активності за останню годину». */
    @Query("SELECT * FROM sensor_records WHERE timestamp >= :since ORDER BY timestamp ASC, id ASC")
    fun observeSince(since: Long): Flow<List<SensorRecord>>

    /** Історія спрацювань системи безпеки: лише записи, коли працював актуатор. */
    @Query(
        "SELECT * FROM sensor_records WHERE actionTriggered = 1 " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit"
    )
    fun observeTriggered(limit: Int): Flow<List<SensorRecord>>

    /** Зведена статистика за період (агрегатні функції SQL рахує сама SQLite). */
    @Query(
        """
        SELECT COUNT(*)                     AS count,
               AVG(sensorValue)             AS average,
               MAX(sensorValue)             AS maximum,
               SUM(actionTriggered)         AS triggeredCount
        FROM sensor_records WHERE timestamp >= :since
        """
    )
    fun observeStats(since: Long): Flow<RecordStats>

    /** Скільки всього рядків у таблиці. */
    @Query("SELECT COUNT(*) FROM sensor_records")
    fun observeCount(): Flow<Int>

    /** Прибирання старих даних, щоб БД не росла безкінечно. Повертає кількість видалених рядків. */
    @Query("DELETE FROM sensor_records WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    /** Очистити таблицю (кнопка «Очистити БД» на екрані «Аналітика»). */
    @Query("DELETE FROM sensor_records")
    suspend fun deleteAll(): Int
}

/**
 * Результат агрегатного запиту [SensorRecordDao.observeStats].
 * Це НЕ таблиця (немає @Entity) — Room просто розкладає колонки SELECT у поля за назвами.
 * AVG, MAX і SUM повертають NULL, коли рядків немає, тому ці поля nullable.
 * (Значень за замовчуванням навмисно немає: тоді в класу один конструктор і Room не вагається.)
 */
data class RecordStats(
    val count: Int,
    val average: Float?,
    val maximum: Float?,
    val triggeredCount: Int?
) {
    companion object {
        val EMPTY = RecordStats(count = 0, average = null, maximum = null, triggeredCount = null)
    }
}

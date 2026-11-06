package com.lab.vibrationanalyzer.repository.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * ЛР5. Database — точка входу в локальну БД `smart_vibration.db`.
 *
 * @Database перелічує всі таблиці (entities) і версію схеми. Якщо колись змінимо
 * SensorRecord (додамо поле), треба підняти version і описати Migration.
 *
 * Синглтон: відкриття SQLite-файлу — дорога операція, а два відкриті з'єднання
 * до одного файлу можуть конфліктувати. Тому на весь процес існує рівно один
 * екземпляр, який повертає [getInstance].
 */
@Database(
    entities = [SensorRecord::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    /** Реалізацію цього методу генерує Room (AppDatabase_Impl). */
    abstract fun sensorRecordDao(): SensorRecordDao

    companion object {
        const val DATABASE_NAME = "smart_vibration.db"

        // @Volatile — зміна поля одразу видна всім потокам (без кешування в регістрах CPU)
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Потокобезпечний синглтон (double-checked locking):
         * 1) швидка перевірка без блокування — у 99,9 % викликів БД уже створена;
         * 2) якщо ні — заходимо в synchronized і перевіряємо ще раз, бо інший потік
         *    міг створити БД, поки ми чекали на блокування.
         */
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,   // НЕ Activity: інакше витік пам'яті
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    // Навчальний проєкт: при зміні version просто перестворюємо таблиці
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

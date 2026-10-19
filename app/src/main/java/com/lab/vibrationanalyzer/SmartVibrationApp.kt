package com.lab.vibrationanalyzer

import android.app.Application
import com.lab.vibrationanalyzer.repository.VibrationRepository

/**
 * Клас Application живе весь час, поки живе процес додатка.
 * Тут створюємо репозиторій в одному екземплярі, щоб усі екрани
 * (Монітор і Смарт-хаб) бачили ті самі дані.
 */
class SmartVibrationApp : Application() {
    val repository: VibrationRepository by lazy { VibrationRepository() }
}

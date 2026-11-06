package com.lab.vibrationanalyzer.analysis

/**
 * Стани обладнання за рівнем вібрації.
 *
 * Порогові значення (Threshold) задаються для середньоквадратичного (RMS)
 * значення динамічного прискорення, тобто прискорення без складової гравітації:
 *  - NORMAL   : RMS < 0.3 м/с²          — обладнання працює штатно;
 *  - ELEVATED : 0.3 ≤ RMS < 1.5 м/с²    — підвищена вібрація, ознаки зносу;
 *  - CRITICAL : RMS ≥ 1.5 м/с²          — критична вібрація, потрібна зупинка.
 */
enum class VibrationState(val title: String, val description: String) {
    NORMAL("Норма", "Вібрація в допустимих межах"),
    ELEVATED("Підвищена", "Ознаки зносу: заплануйте обслуговування"),
    CRITICAL("Критична", "Небезпечний рівень: зупиніть обладнання");

    companion object {
        const val ELEVATED_THRESHOLD = 0.3f   // м/с²
        const val CRITICAL_THRESHOLD = 1.5f   // м/с²

        fun fromRms(rms: Float): VibrationState = when {
            rms >= CRITICAL_THRESHOLD -> CRITICAL
            rms >= ELEVATED_THRESHOLD -> ELEVATED
            else -> NORMAL
        }
    }
}

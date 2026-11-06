package com.lab.vibrationanalyzer.ai

/**
 * ЛР6. Матриця станів: класи, які розрізняє модель, і реакція системи на кожен.
 *
 * Порядок елементів = порядок виходів моделі = рядки assets/labels.txt.
 */
enum class VibrationClass(
    val label: String,        // як у labels.txt
    val title: String,        // для екрана
    val emoji: String,
    val description: String,
    val reaction: String      // що робить система
) {
    CALM(
        "calm", "Спокій", "🟢",
        "Об'єкт нерухомий, у сигналі лише шум сенсора",
        "звичайний режим"
    ),
    TOUCH(
        "touch", "Випадковий дотик", "🟡",
        "Поодинокий поштовх, зсув або поворот пристрою",
        "лише показ, тривоги немає"
    ),
    MACHINE(
        "machine", "Робоча вібрація", "🟠",
        "Тривала рівномірна вібрація: працює обладнання (аналог «Біг» у фітнес-трекері)",
        "прискорений запис у БД (ЛР5)"
    ),
    TAMPER(
        "tamper", "Спроба злому", "🔴",
        "Серія сильних нерегулярних ударів і шум: свердління, підбір, вибивання",
        "АНОМАЛІЯ: сирена + прожектор (ЛР4), прискорений запис"
    );

    companion object {
        fun fromLabel(label: String): VibrationClass? =
            entries.firstOrNull { it.label.equals(label.trim(), ignoreCase = true) }
    }
}

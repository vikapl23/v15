package com.lab.vibrationanalyzer.ai

/**
 * ЛР6. Контракт класифікатора. ViewModel працює з інтерфейсом, а не з TensorFlow Lite напряму:
 * у тестах його легко підмінити фейком, а модель — замінити іншою.
 */
interface IVibrationClassifier : AutoCloseable {
    /** Короткий опис моделі для екрана (файл, форма тензорів). */
    val description: String

    /** Вікно [ModelSpec.INPUT_SIZE] чисел → ймовірності класів у порядку [VibrationClass]. */
    fun predict(window: FloatArray): FloatArray
}

package com.lab.vibrationanalyzer.ai

/**
 * ЛР6. Параметри моделі і передобробки в одному місці.
 *
 * Ті самі числа стоять у скрипті навчання ml/train_vibration_model.py.
 * Якщо змінити щось тут — модель треба перенавчити, інакше вона «бачитиме» інші дані.
 */
object ModelSpec {
    /** Файл моделі в app/src/main/assets. */
    const val MODEL_FILE = "vibration_classifier.tflite"

    /** Назви класів у порядку виходів моделі (по одній на рядок). */
    const val LABELS_FILE = "labels.txt"

    /** Скільки кадрів у вікні: 20 кадрів × 50 мс = 1 секунда руху. */
    const val WINDOW = 20

    /** Канали кадру: динамічне прискорення по X, Y, Z. */
    const val CHANNELS = 3

    /** Розмір входу моделі: [1, 20, 3] = 60 чисел float32. */
    const val INPUT_SIZE = WINDOW * CHANNELS

    /** Тривалість одного кадру, мс (сенсор дає ~50 Гц, модель — 20 кадрів/с). */
    const val FRAME_MS = 50L

    /** Коефіцієнт фільтра гравітації (як у VibrationAnalyzer з ЛР1). */
    const val ALPHA = 0.8f

    /** Нормування: ділимо прискорення на 4 м/с², щоб числа були порядку одиниці. */
    const val SCALE = 4f

    /** Обрізання нормованих значень до ±3 (тобто ±12 м/с²). */
    const val CLIP = 3f

    /** Inference раз на 5 нових кадрів = 4 прогнози за секунду (вікна перекриваються на 75 %). */
    const val HOP_FRAMES = 5
}

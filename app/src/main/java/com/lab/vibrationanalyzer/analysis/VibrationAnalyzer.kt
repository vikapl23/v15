package com.lab.vibrationanalyzer.analysis

import kotlin.math.sqrt

/** Результат аналізу одного відліку акселерометра. */
data class VibrationSnapshot(
    val x: Float,                 // сире прискорення по осі X, м/с²
    val y: Float,                 // сире прискорення по осі Y, м/с²
    val z: Float,                 // сире прискорення по осі Z, м/с²
    val magnitude: Float,         // модуль повного вектора |a|, м/с²
    val dynamic: Float,           // модуль динамічного прискорення (без гравітації), м/с²
    val rms: Float,               // RMS динамічного прискорення у ковзному вікні, м/с²
    val peak: Float,              // максимальне динамічне прискорення у вікні, м/с²
    val state: VibrationState,
    val wearPercent: Float,       // умовний накопичений знос, %
    val history: List<Float>      // останні значення dynamic для графіка
)

/**
 * Аналізатор вібрації. Не залежить від Android API, тому покривається
 * звичайними JUnit-тестами.
 *
 * Алгоритм:
 * 1. Фільтр низьких частот виділяє гравітацію: g = α·g + (1-α)·a.
 * 2. Динамічне прискорення: d = a - g (фільтр високих частот).
 * 3. RMS модуля d у ковзному вікні з [windowSize] відліків згладжує шум
 *    і дає стабільну оцінку інтенсивності вібрації.
 * 4. Стан визначається за порогами [VibrationState.fromRms].
 * 5. Знос накопичується пропорційно часу роботи в станах ELEVATED/CRITICAL.
 */
class VibrationAnalyzer(
    private val windowSize: Int = 50,
    private val alpha: Float = 0.8f,
    private val historySize: Int = 120
) {
    private val gravity = FloatArray(3)
    private var gravityInitialized = false

    private val window = ArrayDeque<Float>()
    private var sumOfSquares = 0.0
    private val history = ArrayDeque<Float>()

    private var lastTimestampNs = 0L
    var wearPercent = 0f
        private set

    /**
     * Обробляє один відлік.
     * @param timestampNs час події в наносекундах (SensorEvent.timestamp)
     */
    fun process(x: Float, y: Float, z: Float, timestampNs: Long): VibrationSnapshot {
        if (!gravityInitialized) {
            // Перший відлік приймаємо за гравітацію, щоб не було хибного піку на старті
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            gravityInitialized = true
        } else {
            gravity[0] = alpha * gravity[0] + (1 - alpha) * x
            gravity[1] = alpha * gravity[1] + (1 - alpha) * y
            gravity[2] = alpha * gravity[2] + (1 - alpha) * z
        }

        val dx = x - gravity[0]
        val dy = y - gravity[1]
        val dz = z - gravity[2]
        val dynamic = sqrt(dx * dx + dy * dy + dz * dz)
        val magnitude = sqrt(x * x + y * y + z * z)

        // Ковзне вікно для RMS
        window.addLast(dynamic)
        sumOfSquares += dynamic.toDouble() * dynamic
        if (window.size > windowSize) {
            val removed = window.removeFirst()
            sumOfSquares -= removed.toDouble() * removed
        }
        val rms = sqrt((sumOfSquares.coerceAtLeast(0.0) / window.size)).toFloat()
        val peak = window.max()
        val state = VibrationState.fromRms(rms)

        // Накопичення зносу (умовна модель): 0.2 %/с у стані ELEVATED, 2 %/с у CRITICAL
        if (lastTimestampNs != 0L && timestampNs > lastTimestampNs) {
            val dtSec = (timestampNs - lastTimestampNs) / 1_000_000_000f
            val rate = when (state) {
                VibrationState.NORMAL -> 0f
                VibrationState.ELEVATED -> WEAR_RATE_ELEVATED
                VibrationState.CRITICAL -> WEAR_RATE_CRITICAL
            }
            wearPercent = (wearPercent + rate * dtSec).coerceAtMost(100f)
        }
        lastTimestampNs = timestampNs

        history.addLast(dynamic)
        if (history.size > historySize) history.removeFirst()

        return VibrationSnapshot(
            x = x, y = y, z = z,
            magnitude = magnitude,
            dynamic = dynamic,
            rms = rms,
            peak = peak,
            state = state,
            wearPercent = wearPercent,
            history = history.toList()
        )
    }

    /** Скидання накопиченого зносу (наприклад, після "ремонту"). */
    fun resetWear() {
        wearPercent = 0f
    }

    companion object {
        const val WEAR_RATE_ELEVATED = 0.2f  // %/с
        const val WEAR_RATE_CRITICAL = 2f    // %/с
    }
}

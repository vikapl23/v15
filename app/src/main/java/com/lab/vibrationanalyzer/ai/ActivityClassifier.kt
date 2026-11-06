package com.lab.vibrationanalyzer.ai

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * ЛР6. ML-Wrapper: завантажує модель TensorFlow Lite з assets у пам'ять і робить inference.
 *
 * Модель: assets/vibration_classifier.tflite (1D-CNN, ~15 КБ, навчена ml/train_vibration_model.py)
 *  - вхідний тензор:  float32 [1, 20, 3] — 20 кадрів (1 с) динамічного прискорення X, Y, Z, поділеного на 4 м/с²;
 *  - вихідний тензор: float32 [1, 4]     — ймовірності (softmax) класів calm, touch, machine, tamper.
 *
 * Усе працює локально, без інтернету (Edge AI). Виклик [predict] ресурсомісткий —
 * ViewModel запускає його в корутині на Dispatchers.Default, а не в UI-потоці.
 * Interpreter не потокобезпечний, тому predict/close синхронізовані.
 */
class ActivityClassifier(context: Context) : IVibrationClassifier {

    private val interpreter: Interpreter = Interpreter(
        loadModelFile(context),
        Interpreter.Options().setNumThreads(2)
    )

    /** Назви класів з assets/labels.txt (бібліотека tensorflow-lite-support). */
    val labels: List<String> = FileUtil.loadLabels(context, ModelSpec.LABELS_FILE)

    private val inputShape: IntArray = interpreter.getInputTensor(0).shape()     // [1, 20, 3]
    private val outputShape: IntArray = interpreter.getOutputTensor(0).shape()   // [1, 4]

    // Буфери створюємо один раз: inference 4 рази/с не повинен засмічувати пам'ять
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(4 * ModelSpec.INPUT_SIZE)        // 60 float × 4 байти
        .order(ByteOrder.nativeOrder())
    private val outputBuffer: TensorBuffer = TensorBuffer.createFixedSize(outputShape, DataType.FLOAT32)

    private val lock = Any()
    private var closed = false

    init {
        check(inputShape.contentEquals(intArrayOf(1, ModelSpec.WINDOW, ModelSpec.CHANNELS))) {
            "модель чекає вхід ${inputShape.contentToString()}, а передобробка дає [1, ${ModelSpec.WINDOW}, ${ModelSpec.CHANNELS}]"
        }
        // Порядок класів у labels.txt має збігатися з enum VibrationClass
        check(labels.map { VibrationClass.fromLabel(it) } == VibrationClass.entries) {
            "labels.txt ($labels) не відповідає VibrationClass"
        }
    }

    override val description: String =
        "${ModelSpec.MODEL_FILE} · вхід float32${inputShape.contentToString()} · вихід float32${outputShape.contentToString()}"

    /** Варіант з методички: вікно → рядок «Робоча вібрація (92%)». */
    fun classify(sensorWindow: FloatArray): String {
        val probabilities = predict(sensorWindow)
        val best = probabilities.indices.maxBy { probabilities[it] }
        return "${VibrationClass.entries[best].title} (${Math.round(probabilities[best] * 100)}%)"
    }

    /** Вікно 60 чисел → масив ймовірностей для кожного класу. */
    override fun predict(window: FloatArray): FloatArray {
        require(window.size == ModelSpec.INPUT_SIZE) {
            "вікно має містити ${ModelSpec.INPUT_SIZE} чисел, а не ${window.size}"
        }
        synchronized(lock) {
            check(!closed) { "класифікатор уже закрито" }
            inputBuffer.rewind()
            inputBuffer.asFloatBuffer().put(window)     // [x0,y0,z0, x1,y1,z1, …] = тензор [1,20,3]
            outputBuffer.buffer.rewind()
            interpreter.run(inputBuffer, outputBuffer.buffer)
            return outputBuffer.floatArray               // [p_calm, p_touch, p_machine, p_tamper]
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                interpreter.close()
                closed = true
            }
        }
    }

    /**
     * Відображає файл моделі з assets прямо в пам'ять (memory-mapped), без копіювання в heap.
     * Працює лише для НЕстиснутих файлів — тому в build.gradle.kts є noCompress += "tflite".
     */
    private fun loadModelFile(context: Context): MappedByteBuffer =
        context.assets.openFd(ModelSpec.MODEL_FILE).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
}

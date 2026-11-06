package com.lab.vibrationanalyzer.ai

/**
 * ЛР6. Кільцевий буфер (Circular Buffer) для вікна даних моделі.
 *
 * Зберігає останні [capacity] кадрів по [channels] чисел у ОДНОМУ масиві фіксованого розміру.
 * Новий кадр перезаписує найстаріший — нічого не зсуваємо і не створюємо нових об'єктів,
 * тому додавання коштує O(1) навіть при 50 відліках за секунду.
 *
 *   data: [кадр3][кадр4][кадр1][кадр2]      head → місце для наступного запису
 *                       ^head
 */
class CircularBuffer(val capacity: Int, val channels: Int) {

    init {
        require(capacity > 0 && channels > 0) { "capacity і channels мають бути > 0" }
    }

    private val data = FloatArray(capacity * channels)
    private var head = 0          // індекс кадру, куди писати наступний

    /** Скільки кадрів зараз у буфері (0..capacity). */
    var size = 0
        private set

    val isFull: Boolean get() = size == capacity

    fun add(frame: FloatArray) {
        require(frame.size == channels) { "кадр має містити $channels значення, а не ${frame.size}" }
        frame.copyInto(data, destinationOffset = head * channels)
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /**
     * Вміст буфера в хронологічному порядку (найстаріший → найновіший), одним масивом
     * [кадр0.x, кадр0.y, кадр0.z, кадр1.x, …] — саме так модель чекає тензор [1, 20, 3].
     */
    fun toFloatArray(): FloatArray {
        val out = FloatArray(size * channels)
        val start = if (isFull) head else 0     // найстаріший кадр
        for (i in 0 until size) {
            val src = ((start + i) % capacity) * channels
            data.copyInto(out, destinationOffset = i * channels, startIndex = src, endIndex = src + channels)
        }
        return out
    }

    fun clear() {
        head = 0
        size = 0
        data.fill(0f)
    }
}

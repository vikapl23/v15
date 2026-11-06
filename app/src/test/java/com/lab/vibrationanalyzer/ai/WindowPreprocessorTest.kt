package com.lab.vibrationanalyzer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ЛР6. Передобробка в додатку має давати ТІ САМІ числа, на яких навчалась модель.
 * Контрольні вектори (preprocess_vectors.csv) згенеровано Python-версією в ml/train_vibration_model.py.
 */
class WindowPreprocessorTest {

    @Test
    fun matchesPythonTrainingPreprocessing() {
        val text = requireNotNull(javaClass.classLoader?.getResource("preprocess_vectors.csv")) {
            "немає app/src/test/resources/preprocess_vectors.csv"
        }.readText()
        val pre = WindowPreprocessor()
        val produced = mutableListOf<FloatArray>()
        val expected = mutableListOf<FloatArray>()
        text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val p = line.split(',')
            when (p[0]) {
                "raw" -> pre.push(p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[1].toLong())?.let(produced::add)
                "frame" -> expected += floatArrayOf(p[2].toFloat(), p[3].toFloat(), p[4].toFloat())
            }
        }
        assertTrue("у векторах мають бути кадри", expected.size >= 20)
        assertEquals(expected.size, produced.size)
        for (i in expected.indices) for (c in 0..2) {
            assertEquals("кадр $i, вісь $c", expected[i][c], produced[i][c], 1e-4f)
        }
    }

    @Test
    fun gravityAloneGivesZeroFrames() {
        val pre = WindowPreprocessor()
        var frame: FloatArray? = null
        for (i in 0..10) frame = pre.push(0f, 9.81f, 0f, i * 20L) ?: frame
        assertNotNull(frame)
        frame!!.forEach { assertEquals(0f, it, 1e-6f) }
    }

    @Test
    fun framesAre50msLong() {
        val pre = WindowPreprocessor()
        var frames = 0
        // 50 Гц протягом 1 секунди → 20 кадрів (перший кадр закривається на 60 мс)
        for (i in 0..50) if (pre.push(0f, 0f, 9.81f, i * 20L) != null) frames++
        assertEquals(20, frames)
    }

    @Test
    fun shortImpulseIsNotLostByDecimation() {
        val pre = WindowPreprocessor()
        val frames = mutableListOf<FloatArray>()
        for (i in 0..20) {
            val impulse = if (i == 7) 8f else 0f          // удар тривалістю ОДИН відлік (20 мс)
            pre.push(impulse, 0f, 9.81f, i * 20L)?.let(frames::add)
        }
        val maxX = frames.maxOf { it[0] }
        // d = 8 − 0.2·8 = 6.4 м/с² → /4 = 1.6
        assertEquals(1.6f, maxX, 1e-4f)
    }

    @Test
    fun valuesAreClipped() {
        val pre = WindowPreprocessor()
        val frames = mutableListOf<FloatArray>()
        for (i in 0..10) pre.push(if (i == 3) 200f else 0f, 0f, 0f, i * 20L)?.let(frames::add)
        assertEquals(ModelSpec.CLIP, frames.maxOf { it[0] }, 0f)
    }

    @Test
    fun firstSampleProducesNoFrame() {
        assertNull(WindowPreprocessor().push(1f, 2f, 3f, 0))
    }
}

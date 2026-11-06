package com.lab.vibrationanalyzer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ЛР6. Сенсор → кадри → кільцевий буфер → вікно для моделі. */
class AiPipelineTest {

    /** 50 Гц: повертає всі вікна, що віддав конвеєр. */
    private fun run(pipeline: AiPipeline, seconds: Int): List<FloatArray> {
        val windows = mutableListOf<FloatArray>()
        for (i in 0..seconds * 50) pipeline.push(0f, 9.81f, 0f, i * 20L)?.let(windows::add)
        return windows
    }

    @Test
    fun firstWindowAfterOneSecondThenFourPerSecond() {
        val p = AiPipeline()
        assertEquals("за 1 с ще немає повних 20 кадрів після першого", 1, run(p, 1).size)
        val p2 = AiPipeline()
        // 3 с = 60 кадрів: перше вікно на 20-му кадрі, далі кожні 5 кадрів → 1 + 8 = 9
        assertEquals(9, run(p2, 3).size)
    }

    @Test
    fun windowHasModelInputSize() {
        val w = run(AiPipeline(), 2).first()
        assertEquals(ModelSpec.INPUT_SIZE, w.size)
        assertEquals(60, w.size)
    }

    @Test
    fun resetStartsOver() {
        val p = AiPipeline()
        run(p, 2)
        p.reset()
        assertEquals(0, p.bufferedFrames)
        assertNull(p.push(0f, 9.81f, 0f, 0))
    }
}

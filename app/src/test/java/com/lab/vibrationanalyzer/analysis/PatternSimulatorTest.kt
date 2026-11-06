package com.lab.vibrationanalyzer.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** ЛР6. Демонстраційні патерни для моделі. */
class PatternSimulatorTest {

    private fun magnitudes(pattern: DemoPattern, seconds: Double = 3.0): List<Float> {
        val sim = PatternSimulator()
        val n = (seconds * 50).toInt()
        return (0 until n).map { i ->
            val o = sim.offset(pattern, i * 20_000_000L)
            sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2])
        }
    }

    @Test
    fun noneAddsNothing() {
        assertTrue(magnitudes(DemoPattern.NONE).all { it == 0f })
    }

    @Test
    fun touchIsSingleShortImpulseEveryThreeSeconds() {
        val m = magnitudes(DemoPattern.TOUCH, 6.0)
        val strong = m.indices.filter { m[it] > 1f }
        assertTrue(strong.isNotEmpty())
        // імпульс на 0 с і на 3 с (групи відліків, між якими > 1 с тиші); кожен коротший за 0.2 с
        val groups = strong.fold(mutableListOf<MutableList<Int>>()) { acc, i ->
            if (acc.isEmpty() || i - acc.last().last() > 50) acc.add(mutableListOf(i)) else acc.last().add(i)
            acc
        }
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.last() - it.first() < 10 })
    }

    @Test
    fun machineIsSustained() {
        val m = magnitudes(DemoPattern.MACHINE)
        val windows = m.chunked(25)            // кожні 0.5 с є помітна вібрація
        assertTrue(windows.all { w -> w.max() > 0.8f })
    }

    @Test
    fun tamperHasManyStrongIrregularHits() {
        val m = magnitudes(DemoPattern.TAMPER)
        // ~4.5 удари/с → за 3 с щонайменше 8 відліків сильніших за 2 м/с²
        val hits = m.count { it > 2f }
        assertTrue("сильних відліків $hits", hits >= 8)
        // між ударами — шум, а не тиша (свердління)
        assertTrue(m.count { it > 0.2f } > m.size / 2)
    }

    @Test
    fun deterministicWithSameSeed() {
        val a = magnitudes(DemoPattern.TAMPER)
        val b = magnitudes(DemoPattern.TAMPER)
        assertTrue(a.zip(b).all { (x, y) -> abs(x - y) < 1e-6f })
    }
}

package com.lab.vibrationanalyzer.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ЛР5. Стиснення ряду для графіка «за годину». */
class ChartDownsamplerTest {

    private fun series(n: Int) = List(n) { i -> ChartPoint(i * 1_000L, (i % 10).toFloat(), false) }

    @Test
    fun shortSeriesIsReturnedUnchanged() {
        val points = series(30)
        assertSame(points, ChartDownsampler.downsample(points, 50))
    }

    @Test
    fun longSeriesIsLimitedToMaxPoints() {
        val result = ChartDownsampler.downsample(series(3_600), 60)
        assertEquals(60, result.size)
        // порядок за часом зберігається
        assertTrue(result.zipWithNext().all { (a, b) -> a.timestampMs < b.timestampMs })
    }

    @Test
    fun peakOfEachGroupIsKept() {
        val points = List(100) { i -> ChartPoint(i.toLong(), if (i == 37) 9f else 0.1f, false) }
        val result = ChartDownsampler.downsample(points, 10)
        assertEquals(10, result.size)
        assertEquals("короткий удар не зникає після стиснення", 9f, result.maxOf { it.value })
        assertEquals(37L, result.first { it.value == 9f }.timestampMs)
    }

    @Test
    fun triggeredFlagSurvivesDownsampling() {
        val points = List(100) { i -> ChartPoint(i.toLong(), 1f, triggered = i == 55) }
        val result = ChartDownsampler.downsample(points, 10)
        assertEquals(1, result.count { it.triggered })
    }

    @Test
    fun unevenSizeIsSplitWithoutLosingPoints() {
        val result = ChartDownsampler.downsample(series(103), 10)
        assertEquals(10, result.size)
    }
}

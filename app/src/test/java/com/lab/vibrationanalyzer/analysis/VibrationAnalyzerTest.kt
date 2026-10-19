package com.lab.vibrationanalyzer.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VibrationAnalyzerTest {

    private val stepNs = 20_000_000L  // 50 Гц

    private fun feed(
        analyzer: VibrationAnalyzer,
        seconds: Float,
        amplitude: Float,
        startNs: Long = 1L
    ): VibrationSnapshot {
        var last: VibrationSnapshot? = null
        val samples = (seconds * 50).toInt()
        for (i in 0 until samples) {
            val t = i * 0.02
            val vib = (amplitude * sin(2 * PI * 7 * t)).toFloat()
            last = analyzer.process(vib, 0f, 9.81f, startNs + i * stepNs)
        }
        return last!!
    }

    @Test
    fun thresholdsClassifyRms() {
        assertEquals(VibrationState.NORMAL, VibrationState.fromRms(0.1f))
        assertEquals(VibrationState.ELEVATED, VibrationState.fromRms(0.3f))
        assertEquals(VibrationState.ELEVATED, VibrationState.fromRms(1.0f))
        assertEquals(VibrationState.CRITICAL, VibrationState.fromRms(1.5f))
    }

    @Test
    fun stillDeviceIsNormalAndDoesNotWear() {
        val s = feed(VibrationAnalyzer(), seconds = 3f, amplitude = 0f)
        assertEquals(VibrationState.NORMAL, s.state)
        assertEquals(0f, s.rms, 1e-4f)
        assertEquals(0f, s.wearPercent, 1e-6f)
        assertEquals(9.81f, s.magnitude, 1e-3f)
    }

    @Test
    fun staticTiltIsNotVibration() {
        val analyzer = VibrationAnalyzer()
        // Пристрій лежить, потім нахилений і лишається нерухомим
        repeat(50) { analyzer.process(0f, 0f, 9.81f, 1L + it * stepNs) }
        var s: VibrationSnapshot? = null
        repeat(150) { s = analyzer.process(6.94f, 0f, 6.94f, 1L + (50 + it) * stepNs) }
        assertEquals(VibrationState.NORMAL, s!!.state)
    }

    @Test
    fun moderateVibrationIsElevated() {
        val s = feed(VibrationAnalyzer(), seconds = 3f, amplitude = 1f)
        assertEquals(VibrationState.ELEVATED, s.state)
    }

    @Test
    fun strongVibrationIsCriticalAndWearsFaster() {
        val elevated = feed(VibrationAnalyzer(), seconds = 3f, amplitude = 1f)
        val critical = feed(VibrationAnalyzer(), seconds = 3f, amplitude = 4f)
        assertEquals(VibrationState.CRITICAL, critical.state)
        assertTrue(critical.wearPercent > elevated.wearPercent)
        assertTrue(critical.peak >= critical.rms)
    }

    @Test
    fun resetWearClearsAccumulatedWear() {
        val analyzer = VibrationAnalyzer()
        feed(analyzer, seconds = 3f, amplitude = 4f)
        assertTrue(analyzer.wearPercent > 0f)
        analyzer.resetWear()
        assertEquals(0f, analyzer.wearPercent, 0f)
    }

    @Test
    fun wearSimulatorPushesAnalyzerIntoCritical() {
        val analyzer = VibrationAnalyzer()
        val simulator = WearSimulator()
        var s: VibrationSnapshot? = null
        for (i in 0 until 150) {
            val ts = 1L + i * stepNs
            val o = simulator.offset(1f, ts)
            s = analyzer.process(o[0], o[1], 9.81f + o[2], ts)
        }
        assertEquals(VibrationState.CRITICAL, s!!.state)
    }

    @Test
    fun historyIsBounded() {
        val s = feed(VibrationAnalyzer(historySize = 120), seconds = 10f, amplitude = 1f)
        assertEquals(120, s.history.size)
    }
}

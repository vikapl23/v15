package com.lab.vibrationanalyzer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ЛР6. Згладжування прогнозів, «аномалія» і «висока активність» з гістерезисом. */
class InsightSmootherTest {

    private val calm = floatArrayOf(0.97f, 0.01f, 0.01f, 0.01f)
    private val touch = floatArrayOf(0.1f, 0.85f, 0.03f, 0.02f)
    private val machine = floatArrayOf(0.02f, 0.03f, 0.92f, 0.03f)
    private val tamper = floatArrayOf(0.01f, 0.04f, 0.05f, 0.90f)

    @Test
    fun calmIsNormalMode() {
        val i = InsightSmoother().update(calm, 0.3f, 0)
        assertEquals(VibrationClass.CALM, i.top)
        assertEquals(0.97f, i.confidence, 1e-6f)
        assertFalse(i.anomaly)
        assertFalse(i.highActivity)
        assertEquals("Спокій (97%)", i.headline)
    }

    @Test
    fun singleTamperPredictionIsNotEnoughForAnomaly() {
        val s = InsightSmoother()
        s.update(calm, 0f, 0)
        s.update(calm, 0f, 250)
        val i = s.update(tamper, 0f, 500)      // (0.01+0.01+0.90)/3 ≈ 0.31 < 0.6
        assertFalse(i.anomaly)
        assertEquals(VibrationClass.TAMPER, i.rawTop)
        assertEquals(VibrationClass.CALM, i.top)
    }

    @Test
    fun stableTamperIsAnomalyAndHighActivity() {
        val s = InsightSmoother()
        s.update(calm, 0f, 0)
        s.update(tamper, 0f, 250)
        val i = s.update(tamper, 0f, 500)      // ≈ 0.60
        assertTrue(i.anomaly)
        assertTrue(i.highActivity)
        assertEquals(VibrationClass.TAMPER, i.top)
    }

    @Test
    fun machineBoostsRecordingButIsNotAnomaly() {
        val s = InsightSmoother()
        repeat(3) { s.update(machine, 0f, it * 250L) }
        val i = s.update(machine, 0f, 750)
        assertTrue(i.highActivity)
        assertFalse(i.anomaly)
        assertEquals(VibrationClass.MACHINE, i.top)
    }

    @Test
    fun hysteresisKeepsAnomalyUntilBelowExit() {
        val s = InsightSmoother()
        repeat(3) { s.update(tamper, 0f, 0) }
        // одна «спокійна» відповідь: середнє (0.9+0.9+0.01)/3 ≈ 0.60 — ще аномалія
        assertTrue(s.update(calm, 0f, 0).anomaly)
        // друга: (0.9+0.01+0.01)/3 ≈ 0.31 < 0.4 — вимикаємо
        assertFalse(s.update(calm, 0f, 0).anomaly)
    }

    @Test
    fun touchDoesNotTriggerAnything() {
        val s = InsightSmoother()
        repeat(3) { s.update(touch, 0f, 0) }
        val i = s.update(touch, 0f, 0)
        assertEquals(VibrationClass.TOUCH, i.top)
        assertFalse(i.anomaly)
        assertFalse(i.highActivity)
        assertEquals(4L, i.count)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongOutputSize() {
        InsightSmoother().update(floatArrayOf(1f, 0f), 0f, 0)
    }
}

package com.lab.vibrationanalyzer.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ЛР4. Логіка «IF rms ≥ threshold THEN activate_actuator» без Android. */
class DecisionEngineTest {

    private val engine = DecisionEngine(threshold = 1.5f, hysteresis = 0.2f, alarmRepeatMs = 5_000)

    private fun decide(rms: Float, t: Long = 0, block: DecisionInput.() -> DecisionInput = { this }) =
        engine.decide(DecisionInput(rms = rms, nowMs = t).block())

    @Test
    fun belowThresholdEverythingOff() {
        val d = decide(0.5f)
        assertFalse(d.thresholdExceeded)
        assertFalse(d.torchOn)
        assertFalse(d.alarmActive)
        assertFalse(d.vibrateNow)
        assertNull(d.torchSource)
    }

    @Test
    fun thresholdActivatesBothActuators() {
        val d = decide(1.5f)
        assertTrue(d.thresholdExceeded)
        assertTrue(d.torchOn)
        assertTrue(d.alarmActive)
        assertTrue(d.vibrateNow)
        assertEquals(CommandSource.AUTO, d.torchSource)
        assertEquals(CommandSource.AUTO, d.alarmSource)
    }

    @Test
    fun hysteresisPreventsFlapping() {
        decide(1.6f)
        // 1.3 — нижче порогу 1.5, але вище порогу вимкнення 1.2 → тривога лишається
        assertTrue(decide(1.3f, 100).alarmActive)
        assertTrue(decide(1.21f, 200).alarmActive)
        // 1.19 < 1.2 → вимикаємо, і просимо зупинити вібрацію
        val off = decide(1.19f, 300)
        assertFalse(off.alarmActive)
        assertTrue(off.stopVibration)
        // а знову 1.3 вже не вмикає (засувка скинута)
        assertFalse(decide(1.3f, 400).alarmActive)
    }

    @Test
    fun vibrationRepeatsEveryFiveSecondsWhileActive() {
        var count = 0
        for (i in 0..600) {                 // 12 с при 50 Гц
            if (decide(2f, i * 20L).vibrateNow) count++
        }
        assertEquals(3, count)              // 0 с, 5 с, 10 с
    }

    @Test
    fun cloudCommandTurnsTorchOnWithoutThreshold() {
        val d = decide(0.1f) { copy(cloudTorch = true) }
        assertTrue(d.torchOn)
        assertEquals(CommandSource.CLOUD, d.torchSource)
        assertFalse(d.alarmActive)
    }

    @Test
    fun cloudAlarmTriggersVibration() {
        val d = decide(0.1f) { copy(cloudAlarm = true) }
        assertTrue(d.alarmActive)
        assertTrue(d.vibrateNow)
        assertEquals(CommandSource.CLOUD, d.alarmSource)
    }

    @Test
    fun manualOffOverridesCloudAndThreshold() {
        val d = decide(3f) { copy(cloudTorch = true, manualTorch = ManualMode.OFF) }
        assertFalse(d.torchOn)
        assertEquals(CommandSource.MANUAL, d.torchSource)
        assertTrue(d.alarmActive)           // сирена лишилась в «Авто»
    }

    @Test
    fun manualOnWorksInNormalState() {
        val d = decide(0f) { copy(manualTorch = ManualMode.ON, manualAlarm = ManualMode.ON) }
        assertTrue(d.torchOn)
        assertTrue(d.alarmActive)
        assertEquals(CommandSource.MANUAL, d.alarmSource)
    }

    @Test
    fun disabledAutomationIgnoresThreshold() {
        val d = decide(3f) { copy(autoEnabled = false) }
        assertTrue(d.thresholdExceeded)
        assertFalse(d.torchOn)
        assertFalse(d.alarmActive)
        assertTrue(d.reason.contains("автоматику вимкнено"))
    }

    @Test
    fun cloudHasPriorityOverAutoInSource() {
        val d = decide(3f) { copy(cloudTorch = true) }
        assertEquals(CommandSource.CLOUD, d.torchSource)
        assertEquals(CommandSource.AUTO, d.alarmSource)
    }

    @Test
    fun thresholdIsClamped() {
        engine.threshold = 100f
        assertEquals(DecisionEngine.MAX_THRESHOLD, engine.threshold)
        engine.threshold = 0f
        assertEquals(DecisionEngine.MIN_THRESHOLD, engine.threshold)
    }

    @Test
    fun customThresholdFromCloud() {
        engine.threshold = 0.5f
        assertTrue(decide(0.6f).alarmActive)
        assertEquals(0.4f, engine.releaseThreshold, 1e-6f)
    }
}

package com.lab.vibrationanalyzer.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ЛР5. Правило «чи писати відлік у БД». */
class RecordingPolicyTest {

    private fun policy() = RecordingPolicy(
        minDelta = 0.05f, minIntervalMs = 200, heartbeatMs = 5_000, timerIntervalMs = 1_000
    )

    @Test
    fun firstReadingIsRecorded() {
        assertTrue(policy().shouldRecord(0.1f, triggered = false, nowMs = 1_000))
    }

    @Test
    fun smallChangeIsNotRecorded() {
        val p = policy()
        p.shouldRecord(0.10f, false, 0)
        assertFalse(p.shouldRecord(0.12f, false, 1_000))
        assertFalse(p.shouldRecord(0.14f, false, 2_000))
    }

    @Test
    fun significantChangeIsRecorded() {
        val p = policy()
        p.shouldRecord(0.10f, false, 0)
        assertTrue(p.shouldRecord(0.40f, false, 300))
    }

    @Test
    fun significantChangeRespectsMinInterval() {
        val p = policy()
        p.shouldRecord(0.10f, false, 0)
        assertFalse("частіше ніж раз на 200 мс не пишемо", p.shouldRecord(0.90f, false, 100))
        assertTrue(p.shouldRecord(0.90f, false, 200))
    }

    @Test
    fun deltaIsMeasuredFromLastRecordedValue() {
        val p = policy()
        p.shouldRecord(0.10f, false, 0)
        // кожен крок по 0.03 — менше порогу, але від останнього ЗАПИСАНОГО 0.10 до 0.16 вже 0.06
        assertFalse(p.shouldRecord(0.13f, false, 300))
        assertTrue(p.shouldRecord(0.16f, false, 600))
    }

    @Test
    fun heartbeatRecordsEvenWithoutChange() {
        val p = policy()
        p.shouldRecord(0.10f, false, 0)
        assertFalse(p.shouldRecord(0.10f, false, 4_999))
        assertTrue(p.shouldRecord(0.10f, false, 5_000))
    }

    @Test
    fun actuatorChangeIsRecordedImmediately() {
        val p = policy()
        p.shouldRecord(1.60f, false, 0)
        assertTrue("увімкнення тривоги", p.shouldRecord(1.60f, true, 10))
        assertFalse(p.shouldRecord(1.60f, true, 20))
        assertTrue("вимкнення тривоги", p.shouldRecord(1.60f, false, 30))
    }

    @Test
    fun timerModeRecordsOncePerInterval() {
        val p = policy().apply { mode = RecordingMode.TIMER }
        assertTrue(p.shouldRecord(0.1f, false, 0))
        assertFalse("велика зміна в режимі таймера не важлива", p.shouldRecord(2.0f, false, 500))
        assertTrue(p.shouldRecord(2.0f, false, 1_000))
        assertFalse(p.shouldRecord(2.0f, false, 1_999))
        assertTrue(p.shouldRecord(2.0f, false, 2_000))
    }

    @Test
    fun resetMakesNextReadingFirst() {
        val p = policy()
        p.shouldRecord(0.1f, false, 0)
        p.reset()
        assertTrue(p.shouldRecord(0.1f, false, 10))
    }

    // ---------- ЛР6: прискорений запис за сигналом моделі ----------

    @Test
    fun boostedModeRecordsMoreOften() {
        val p = policy()
        p.shouldRecord(0.10f, false, 0)
        assertFalse("звичайний режим: 100 мс < 200 мс", p.shouldRecord(0.50f, false, 100))
        p.boosted = true
        assertTrue("прискорений режим: 100 мс ≥ 50 мс", p.shouldRecord(0.50f, false, 100))
        assertTrue(p.shouldRecord(0.90f, false, 160))
    }

    @Test
    fun boostedHeartbeatIsOneSecond() {
        val p = policy()
        p.boosted = true
        p.shouldRecord(0.10f, false, 0)
        assertFalse(p.shouldRecord(0.10f, false, 900))
        assertTrue(p.shouldRecord(0.10f, false, 1_000))
    }

    @Test
    fun boostedTimerIsFiveTimesPerSecond() {
        val p = policy()
        p.mode = RecordingMode.TIMER
        p.boosted = true
        p.shouldRecord(0.10f, false, 0)
        assertFalse(p.shouldRecord(0.10f, false, 150))
        assertTrue(p.shouldRecord(0.10f, false, 200))
        assertEquals(200L, p.currentTimerIntervalMs)
    }
}

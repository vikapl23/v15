package com.lab.vibrationanalyzer.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ЛР3. Правило «раз на 5 секунд або при зміні на Δ». */
class SyncPolicyTest {

    private fun data(value: Float = 0.2f, x: Float = 0f, y: Float = 9.8f, z: Float = 0f) =
        SensorData("vibro-15", 0L, SensorData.TYPE_ACCELEROMETER, value, x, y, z, "NORMAL", 0f)

    @Test
    fun firstReadingIsSentImmediately() {
        assertEquals(SyncPolicy.Reason.FIRST, SyncPolicy().check(data(), nowMs = 0))
    }

    @Test
    fun sameValueIsNotSentBeforeInterval() {
        val policy = SyncPolicy(intervalMs = 5_000)
        policy.markSent(data(), 0)
        assertNull(policy.check(data(), 1_000))
        assertNull(policy.check(data(), 4_999))
        assertEquals(SyncPolicy.Reason.INTERVAL, policy.check(data(), 5_000))
    }

    @Test
    fun bigChangeOfCoordinateIsSentEarly() {
        val policy = SyncPolicy(intervalMs = 5_000, delta = 1.0f, minGapMs = 1_000)
        policy.markSent(data(y = 9.8f), 0)
        // Повернули телефон: гравітація перейшла з осі Y на вісь X
        val rotated = data(x = 9.8f, y = 0f)
        assertNull("не частіше ніж раз на секунду", policy.check(rotated, 500))
        assertEquals(SyncPolicy.Reason.DELTA, policy.check(rotated, 1_000))
    }

    @Test
    fun smallChangeIsNotSentEarly() {
        val policy = SyncPolicy(intervalMs = 5_000, delta = 1.0f)
        policy.markSent(data(value = 0.2f), 0)
        assertNull(policy.check(data(value = 0.9f, x = 0.5f), 2_000))
    }

    @Test
    fun rmsChangeAboveDeltaIsSentEarly() {
        val policy = SyncPolicy(intervalMs = 5_000, delta = 1.0f)
        policy.markSent(data(value = 0.2f), 0)
        assertEquals(SyncPolicy.Reason.DELTA, policy.check(data(value = 1.7f), 2_000))
    }
}

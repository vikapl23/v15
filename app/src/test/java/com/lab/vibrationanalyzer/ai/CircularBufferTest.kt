package com.lab.vibrationanalyzer.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ЛР6. Кільцевий буфер для вікна моделі. */
class CircularBufferTest {

    private fun frame(v: Float) = floatArrayOf(v, v + 0.1f, v + 0.2f)

    @Test
    fun fillsUpToCapacity() {
        val b = CircularBuffer(capacity = 3, channels = 3)
        assertEquals(0, b.size)
        b.add(frame(1f)); b.add(frame(2f))
        assertFalse(b.isFull)
        b.add(frame(3f))
        assertTrue(b.isFull)
        assertArrayEquals(frame(1f) + frame(2f) + frame(3f), b.toFloatArray(), 1e-6f)
    }

    @Test
    fun overwritesOldestAndKeepsChronologicalOrder() {
        val b = CircularBuffer(capacity = 3, channels = 3)
        for (i in 1..5) b.add(frame(i.toFloat()))
        assertEquals(3, b.size)
        // залишились 3, 4, 5 — від найстарішого до найновішого
        assertArrayEquals(frame(3f) + frame(4f) + frame(5f), b.toFloatArray(), 1e-6f)
    }

    @Test
    fun snapshotIsACopy() {
        val b = CircularBuffer(capacity = 2, channels = 3)
        b.add(frame(1f)); b.add(frame(2f))
        val snapshot = b.toFloatArray()
        b.add(frame(9f))
        assertEquals(1f, snapshot[0], 0f)
    }

    @Test
    fun clearEmptiesBuffer() {
        val b = CircularBuffer(capacity = 2, channels = 3)
        b.add(frame(1f))
        b.clear()
        assertEquals(0, b.size)
        assertEquals(0, b.toFloatArray().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongFrameSize() {
        CircularBuffer(capacity = 2, channels = 3).add(floatArrayOf(1f))
    }
}

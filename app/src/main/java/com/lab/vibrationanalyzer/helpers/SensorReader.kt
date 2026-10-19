package com.lab.vibrationanalyzer.helpers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Один відлік акселерометра: прискорення по осях (м/с²) і час події (нс). */
data class AccelSample(val x: Float, val y: Float, val z: Float, val timestampNs: Long)

/**
 * Помічник, що перетворює SensorManager (Android API) на Flow.
 *
 * Поки Flow збирають (collect), слухач зареєстрований. Коли ViewModel
 * скасовує збір (onPause → Action Stop), спрацьовує awaitClose і
 * слухач знімається — сенсор не витрачає батарею у фоні.
 */
class SensorReader(context: Context) {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** true, якщо на пристрої (емуляторі) є акселерометр. */
    val isAvailable: Boolean get() = sensor != null

    fun readings(): Flow<AccelSample> = callbackFlow {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // values[0..2] — прискорення по X, Y, Z у м/с² (з урахуванням гравітації)
                trySend(AccelSample(e.values[0], e.values[1], e.values[2], e.timestamp))
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        // SENSOR_DELAY_GAME ≈ 50 Гц — достатньо для аналізу вібрації
        val registered = sensor != null &&
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        Log.d(TAG, "registerListener: $registered")
        awaitClose {
            manager.unregisterListener(listener)
            Log.d(TAG, "unregisterListener")
        }
    }

    private companion object {
        const val TAG = "SensorReader"
    }
}

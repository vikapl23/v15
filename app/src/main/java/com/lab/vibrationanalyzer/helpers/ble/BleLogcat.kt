package com.lab.vibrationanalyzer.helpers.ble

import android.util.Log

/** Вивід журналу обміну в Logcat. Фільтр у Android Studio: tag:BLE_MOCK */
object BleLogcat {
    const val TAG = "BLE_MOCK"

    fun write(entry: BleLogEntry) {
        val line = "[${entry.kind}] ${entry.message}"
        when (entry.kind) {
            BleLogKind.ERR -> Log.w(TAG, line)
            else -> Log.d(TAG, line)
        }
    }
}

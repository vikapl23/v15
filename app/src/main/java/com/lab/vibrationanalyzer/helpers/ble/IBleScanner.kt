package com.lab.vibrationanalyzer.helpers.ble

import kotlinx.coroutines.flow.Flow

/** Сканер BLE-пристроїв. Flow видає пристрої в міру знаходження і завершується разом зі скануванням. */
interface IBleScanner {
    fun scan(): Flow<BleDevice>
}

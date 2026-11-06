package com.lab.vibrationanalyzer.helpers.ble

import java.util.UUID

/** Пристрій, знайдений під час сканування (аналог ScanResult в Android). */
data class BleDevice(
    val name: String,
    val address: String,        // MAC-адреса
    val rssi: Int,              // рівень сигналу, дБм (ближче до 0 — сильніший)
    val serviceUuids: List<UUID>
) {
    /** Чи рекламує пристрій наш сервіс — лише з таким можна працювати. */
    val isSupported: Boolean get() = VibroHubProfile.SERVICE_UUID in serviceUuids
}

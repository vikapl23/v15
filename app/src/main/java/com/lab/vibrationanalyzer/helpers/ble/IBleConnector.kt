package com.lab.vibrationanalyzer.helpers.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Стани з'єднання (state machine). Сканування — окремий стан екрана. */
enum class BleConnectionState(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting…"),
    CONNECTED("Connected"),
    DATA_TRANSFER("Connected · Data transfer"),
    RECONNECTING("Reconnecting…");

    /** З'єднання встановлене (зокрема під час передачі даних). */
    val isConnected: Boolean get() = this == CONNECTED || this == DATA_TRANSFER
}

enum class BleLogKind { SYS, TX, RX, ERR }

/** Один рядок журналу обміну: показується в терміналі і йде в Logcat. */
data class BleLogEntry(
    val kind: BleLogKind,
    val message: String,
    val timeMs: Long = System.currentTimeMillis()
)

/**
 * Bluetooth Interface Layer: абстракція з'єднання з BLE-пристроєм.
 *
 * ViewModel працює лише з цим інтерфейсом, тому Mock-реалізацію можна
 * замінити на справжню (BluetoothGatt) без змін в UI та ViewModel.
 * Усі довгі операції — suspend-функції: BLE за природою асинхронний.
 */
interface IBleConnector {

    /** Поточний стан з'єднання — UI підписується на нього. */
    val connectionState: StateFlow<BleConnectionState>

    /** Повідомлення від пристрою (Notify характеристики STATUS). */
    val notifications: Flow<String>

    /** Журнал обміну для термінала та Logcat. */
    val logs: Flow<BleLogEntry>

    /** Підключення + пошук сервісів + увімкнення Notify. */
    suspend fun connect(device: BleDevice): Result<Unit>

    /** Розірвати з'єднання і звільнити ресурси. */
    fun disconnect()

    /** Відправити дані на пристрій і дочекатися підтвердження (ACK). */
    suspend fun sendData(data: String): Result<Unit>
}

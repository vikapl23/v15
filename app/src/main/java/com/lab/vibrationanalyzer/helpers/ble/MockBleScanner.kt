package com.lab.vibrationanalyzer.helpers.ble

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID
import kotlin.random.Random

/**
 * Імітація сканування: через [discoveryDelayMs] (2 с) «знаходить» наш
 * віртуальний смарт-хаб, трохи пізніше — сторонній пристрій без нашого
 * сервісу. Сканування триває [scanWindowMs], потім Flow завершується
 * (у справжньому Android сканування теж обмежують за часом, щоб не
 * розряджати батарею).
 */
class MockBleScanner(
    private val discoveryDelayMs: Long = 2_000,
    private val scanWindowMs: Long = 4_000,
    private val random: Random = Random.Default
) : IBleScanner {

    override fun scan(): Flow<BleDevice> = flow {
        delay(discoveryDelayMs)
        emit(VibroHubProfile.virtualDevice.copy(rssi = -52 - random.nextInt(12)))

        delay(OTHER_DEVICE_DELAY_MS)
        emit(
            BleDevice(
                name = "Mi Smart Band 7",
                address = "E4:5F:01:A2:33:7C",
                rssi = -74 - random.nextInt(10),
                serviceUuids = listOf(HEART_RATE_SERVICE)
            )
        )

        delay((scanWindowMs - discoveryDelayMs - OTHER_DEVICE_DELAY_MS).coerceAtLeast(0))
    }

    private companion object {
        const val OTHER_DEVICE_DELAY_MS = 700L
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    }
}

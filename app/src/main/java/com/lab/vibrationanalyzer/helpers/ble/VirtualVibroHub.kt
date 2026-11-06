package com.lab.vibrationanalyzer.helpers.ble

import java.util.UUID

/**
 * Віртуальний IoT-пристрій — «прошивка» смарт-хаба VibroHub-15.
 * Поводиться як GATT-сервер: відповідає на читання STATUS і на запис
 * у COMMAND / TELEMETRY, перевіряє контрольну суму кадрів.
 */
class VirtualVibroHub(batteryPercent: Int = 87) {

    var relayOn = true
        private set
    var batteryPercent = batteryPercent
        private set
    var packetsReceived = 0
        private set
    var lastTelemetry: String? = null
        private set

    private var ticks = 0

    /** Значення характеристики STATUS (Read). */
    fun readStatus(): String =
        "STATUS:relay=${if (relayOn) "ON" else "OFF"};battery=$batteryPercent;rx=$packetsReceived"

    /**
     * Запис у характеристику. Повертає повідомлення, які хаб надішле
     * через Notify: спершу ACK/NAK з номером кадру, далі — відповідь.
     */
    fun onWrite(characteristic: UUID, raw: String): List<String> {
        val frame = VibroHubProfile.parseFrame(raw) ?: return listOf("NAK:?:FORMAT")
        if (!frame.checksumOk) return listOf("NAK:${frame.seq}:CRC")
        val ack = "ACK:${frame.seq}"
        packetsReceived++

        return when (characteristic) {
            VibroHubProfile.TELEMETRY_UUID -> {
                lastTelemetry = frame.payload
                listOf(ack)
            }
            VibroHubProfile.COMMAND_UUID -> when (frame.payload) {
                VibroHubProfile.CMD_PING -> listOf(ack, "PONG")
                VibroHubProfile.CMD_RELAY_ON -> {
                    relayOn = true
                    listOf(ack, readStatus())
                }
                VibroHubProfile.CMD_RELAY_OFF -> {
                    relayOn = false
                    listOf(ack, readStatus())
                }
                VibroHubProfile.CMD_STATUS -> listOf(ack, readStatus())
                else -> {
                    packetsReceived--
                    listOf("NAK:${frame.seq}:UNKNOWN_CMD")
                }
            }
            else -> listOf("NAK:${frame.seq}:NOT_WRITABLE")
        }
    }

    /** Періодичне повідомлення про стан (Notify без запиту); батарея повільно сідає. */
    fun tick(): String {
        ticks++
        if (ticks % BATTERY_DRAIN_TICKS == 0) batteryPercent = (batteryPercent - 1).coerceAtLeast(0)
        return readStatus()
    }

    private companion object {
        const val BATTERY_DRAIN_TICKS = 6
    }
}

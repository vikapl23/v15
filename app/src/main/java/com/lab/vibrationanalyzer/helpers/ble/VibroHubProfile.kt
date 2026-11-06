package com.lab.vibrationanalyzer.helpers.ble

import java.util.Locale
import java.util.UUID

/**
 * GATT-профіль віртуального смарт-хаба «VibroHub-15» і формат пакетів.
 *
 * Смарт-хаб — це розумне реле, що живить двигун обладнання. Додаток
 * надсилає на нього телеметрію вібрації та команди (увімкнути / вимкнути
 * реле), а хаб відповідає підтвердженнями і повідомляє свій стан (Notify).
 *
 * UUID власного сервісу — випадкові 128-бітні (стандартні 16-бітні UUID
 * виду 0000xxxx-0000-1000-8000-00805f9b34fb зарезервовані Bluetooth SIG).
 */
object VibroHubProfile {

    const val DEVICE_NAME = "VibroHub-15"
    const val DEVICE_ADDRESS = "C0:FF:EE:15:15:01"

    /** Сервіс «Vibration Hub Service». */
    val SERVICE_UUID: UUID = UUID.fromString("6e5a1500-7b3c-4f1a-9d2e-15a0b1c2d3e4")

    /** STATUS (Read, Notify): стан хаба, підтвердження ACK/NAK, відповідь PONG. */
    val STATUS_UUID: UUID = UUID.fromString("6e5a1501-7b3c-4f1a-9d2e-15a0b1c2d3e4")

    /** COMMAND (Write): команди керування PING, RELAY:ON, RELAY:OFF, STATUS?. */
    val COMMAND_UUID: UUID = UUID.fromString("6e5a1502-7b3c-4f1a-9d2e-15a0b1c2d3e4")

    /** TELEMETRY (Write): пакети з даними вібрації VIB:… від додатка. */
    val TELEMETRY_UUID: UUID = UUID.fromString("6e5a1503-7b3c-4f1a-9d2e-15a0b1c2d3e4")

    /** Стандартний дескриптор CCCD: запис 01-00 вмикає Notify. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** MTU, яке «погоджує» з'єднання (за замовчуванням у BLE лише 23 байти). */
    const val MTU = 185

    const val CMD_PING = "PING"
    const val CMD_RELAY_ON = "RELAY:ON"
    const val CMD_RELAY_OFF = "RELAY:OFF"
    const val CMD_STATUS = "STATUS?"

    private const val TELEMETRY_PREFIX = "VIB:"

    /** Наш віртуальний пристрій, яким його «бачить» сканер. */
    val virtualDevice = BleDevice(
        name = DEVICE_NAME,
        address = DEVICE_ADDRESS,
        rssi = -58,
        serviceUuids = listOf(SERVICE_UUID)
    )

    fun nameOf(uuid: UUID): String = when (uuid) {
        SERVICE_UUID -> "VIBRATION_HUB"
        STATUS_UUID -> "STATUS"
        COMMAND_UUID -> "COMMAND"
        TELEMETRY_UUID -> "TELEMETRY"
        CCCD_UUID -> "CCCD"
        else -> uuid.toString()
    }

    /** Пакет телеметрії: VIB:rms=0.42;pk=1.10;st=ELEVATED;wear=3.2 */
    fun telemetry(rms: Float, peak: Float, state: String, wearPercent: Float): String =
        String.format(
            Locale.US, "%srms=%.2f;pk=%.2f;st=%s;wear=%.1f",
            TELEMETRY_PREFIX, rms, peak, state, wearPercent
        )

    /** Телеметрія пишеться в TELEMETRY, усе інше — у COMMAND. */
    fun characteristicFor(data: String): UUID =
        if (data.startsWith(TELEMETRY_PREFIX)) TELEMETRY_UUID else COMMAND_UUID

    /** Контрольна сума: XOR усіх символів, 2 hex-цифри (як у протоколі NMEA). */
    fun checksum(body: String): String =
        "%02X".format(body.fold(0) { acc, c -> acc xor c.code } and 0xFF)

    /** Кадр: <номер>|<дані>*<контрольна сума>, наприклад 7|RELAY:OFF*7D */
    fun frame(seq: Int, payload: String): String {
        val body = "$seq|$payload"
        return "$body*${checksum(body)}"
    }

    data class Frame(val seq: Int, val payload: String, val checksumOk: Boolean)

    fun parseFrame(raw: String): Frame? {
        val star = raw.lastIndexOf('*')
        val bar = raw.indexOf('|')
        if (star < 0 || bar < 0 || bar > star) return null
        val seq = raw.substring(0, bar).toIntOrNull() ?: return null
        val body = raw.substring(0, star)
        return Frame(seq, raw.substring(bar + 1, star), checksum(body) == raw.substring(star + 1))
    }

    /** Розбір повідомлення STATUS:relay=ON;battery=87;rx=3 */
    fun parseStatus(message: String): HubStatus? {
        if (!message.startsWith("STATUS:")) return null
        val fields = message.removePrefix("STATUS:").split(';')
            .mapNotNull { part -> part.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
        return HubStatus(
            relayOn = fields["relay"] == "ON",
            batteryPercent = fields["battery"]?.toIntOrNull() ?: return null,
            packetsReceived = fields["rx"]?.toIntOrNull() ?: 0
        )
    }
}

/** Стан смарт-хаба, отриманий через характеристику STATUS. */
data class HubStatus(
    val relayOn: Boolean,
    val batteryPercent: Int,
    val packetsReceived: Int
)

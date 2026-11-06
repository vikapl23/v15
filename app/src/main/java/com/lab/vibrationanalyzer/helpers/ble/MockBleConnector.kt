package com.lab.vibrationanalyzer.helpers.ble

import com.lab.vibrationanalyzer.helpers.ble.VibroHubProfile.nameOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Mock-реалізація [IBleConnector]: імітує BluetoothGatt і радіоканал до
 * віртуального хаба [hub]. Затримки (delay) відтворюють асинхронність BLE.
 *
 * Механізми стабільності зв'язку:
 *  - кожен кадр має номер і контрольну суму, хаб підтверджує його ACK;
 *  - якщо ACK не прийшов за [ACK_TIMEOUT_MS] або прийшов NAK:CRC — повтор (до [MAX_ATTEMPTS]);
 *  - одночасно виконується лише одна GATT-операція (Mutex), як і в Android;
 *  - при обриві зв'язку — автоматичне перепідключення з експоненційною затримкою.
 *
 * Режим [interference] вмикає «перешкоди»: втрату, пошкодження пакетів і обриви.
 *
 * @param scope область корутин, у якій живуть фонові задачі (у ViewModel — viewModelScope)
 */
class MockBleConnector(
    private val scope: CoroutineScope,
    private val hub: VirtualVibroHub = VirtualVibroHub(),
    private val random: Random = Random.Default
) : IBleConnector {

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _notifications = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val notifications: SharedFlow<String> = _notifications.asSharedFlow()

    private val _logs = MutableSharedFlow<BleLogEntry>(extraBufferCapacity = 256)
    override val logs: SharedFlow<BleLogEntry> = _logs.asSharedFlow()

    /** Імітація радіоперешкод. */
    @Volatile
    var interference: Boolean = false

    private val gattLock = Mutex()
    private var sequence = 0
    private var device: BleDevice? = null
    private var notifyJob: Job? = null
    private var reconnectJob: Job? = null

    override suspend fun connect(device: BleDevice): Result<Unit> {
        if (_connectionState.value != BleConnectionState.DISCONNECTED) {
            return Result.failure(IllegalStateException("З'єднання вже встановлюється"))
        }
        this.device = device
        _connectionState.value = BleConnectionState.CONNECTING
        log(BleLogKind.SYS, "connectGatt(${device.address}, autoConnect=false)")
        delay(CONNECT_DELAY_MS)
        if (_connectionState.value != BleConnectionState.CONNECTING) {
            return Result.failure(IllegalStateException("Підключення скасовано"))
        }
        log(BleLogKind.SYS, "onConnectionStateChange: STATE_CONNECTED (GATT_SUCCESS)")

        if (!discoverServices(device)) {
            log(BleLogKind.ERR, "Сервіс ${VibroHubProfile.SERVICE_UUID} не знайдено — disconnect()")
            _connectionState.value = BleConnectionState.DISCONNECTED
            this.device = null
            return Result.failure(UnsupportedOperationException("${device.name} не підтримує сервіс VibroHub"))
        }
        if (_connectionState.value != BleConnectionState.CONNECTING) {
            return Result.failure(IllegalStateException("Підключення скасовано"))
        }
        _connectionState.value = BleConnectionState.CONNECTED
        startStatusNotifications()
        return Result.success(Unit)
    }

    override fun disconnect() {
        reconnectJob?.cancel()
        notifyJob?.cancel()
        if (_connectionState.value == BleConnectionState.DISCONNECTED) return
        log(BleLogKind.SYS, "disconnect() → close(): ресурси GATT звільнено")
        _connectionState.value = BleConnectionState.DISCONNECTED
        device = null
    }

    override suspend fun sendData(data: String): Result<Unit> {
        // Одночасно — лише одна GATT-операція (як у BluetoothGatt), решта чекають у черзі
        return gattLock.withLock { transfer(data) }
    }

    private suspend fun transfer(data: String): Result<Unit> {
        if (_connectionState.value != BleConnectionState.CONNECTED) {
            log(BleLogKind.ERR, "sendData(\"$data\") відхилено: стан ${_connectionState.value.label}")
            return Result.failure(IllegalStateException("Немає з'єднання"))
        }
        val seq = ++sequence
        val frame = VibroHubProfile.frame(seq, data)
        val target = VibroHubProfile.characteristicFor(data)
        _connectionState.value = BleConnectionState.DATA_TRANSFER
        try {
            for (attempt in 1..MAX_ATTEMPTS) {
                val retry = if (attempt > 1) " (повтор $attempt/$MAX_ATTEMPTS)" else ""
                log(BleLogKind.TX, "WRITE ${nameOf(target)} ← $frame$retry")
                delay(LINK_LATENCY_MS)

                if (interference && random.nextFloat() < LINK_DROP_PROBABILITY) {
                    onLinkLost()
                    return Result.failure(IllegalStateException("З'єднання втрачено"))
                }
                if (interference && random.nextFloat() < PACKET_LOSS_PROBABILITY) {
                    delay(ACK_TIMEOUT_MS)
                    log(BleLogKind.ERR, "ACK:$seq не отримано за $ACK_TIMEOUT_MS мс (пакет втрачено)")
                    continue
                }
                val onAir = if (interference && random.nextFloat() < CORRUPTION_PROBABILITY) {
                    corrupt(frame)
                } else {
                    frame
                }

                val replies = hub.onWrite(target, onAir)
                replies.forEach { deliver(it) }
                val first = replies.first()
                when {
                    first == "ACK:$seq" -> return Result.success(Unit)
                    // кадр пошкоджено в ефірі — хаб просить повтор
                    first.endsWith(":CRC") || first.endsWith(":FORMAT") -> continue
                    else -> return Result.failure(IllegalArgumentException("Хаб відхилив команду: $first"))
                }
            }
            log(BleLogKind.ERR, "Кадр #$seq не доставлено після $MAX_ATTEMPTS спроб")
            return Result.failure(IllegalStateException("Пакет не доставлено"))
        } finally {
            if (_connectionState.value == BleConnectionState.DATA_TRANSFER) {
                _connectionState.value = BleConnectionState.CONNECTED
            }
        }
    }

    /** Пошук сервісів, погодження MTU, увімкнення Notify, перше читання STATUS. */
    private suspend fun discoverServices(device: BleDevice): Boolean {
        log(BleLogKind.SYS, "discoverServices()")
        delay(DISCOVERY_DELAY_MS)
        if (!device.isSupported) return false
        log(BleLogKind.SYS, "onServicesDiscovered: ${nameOf(VibroHubProfile.SERVICE_UUID)} ${VibroHubProfile.SERVICE_UUID}")
        listOf(VibroHubProfile.STATUS_UUID, VibroHubProfile.COMMAND_UUID, VibroHubProfile.TELEMETRY_UUID)
            .forEach { log(BleLogKind.SYS, "  характеристика ${nameOf(it)} $it") }
        log(BleLogKind.SYS, "requestMtu(${VibroHubProfile.MTU}) → onMtuChanged: ${VibroHubProfile.MTU}")
        log(BleLogKind.SYS, "setCharacteristicNotification(STATUS, true); CCCD 0x2902 ← 01-00")
        val status = hub.readStatus()
        log(BleLogKind.RX, "READ STATUS → $status")
        _notifications.tryEmit(status)
        return true
    }

    /** Хаб сам повідомляє свій стан кожні [STATUS_PERIOD_MS] (Notify). */
    private fun startStatusNotifications() {
        notifyJob?.cancel()
        notifyJob = scope.launch {
            while (isActive) {
                delay(STATUS_PERIOD_MS)
                if (_connectionState.value.isConnected) {
                    val status = hub.tick()
                    if (interference && random.nextFloat() < PACKET_LOSS_PROBABILITY) {
                        log(BleLogKind.ERR, "NOTIFY STATUS втрачено (перешкоди)")
                    } else {
                        deliver(status)
                    }
                }
            }
        }
    }

    /** Обрив зв'язку: пробуємо перепідключитися із затримкою 0.5 → 1 → 2 с. */
    private fun onLinkLost() {
        notifyJob?.cancel()
        _connectionState.value = BleConnectionState.RECONNECTING
        log(BleLogKind.ERR, "onConnectionStateChange: STATE_DISCONNECTED (status 8: GATT_CONN_TIMEOUT)")
        val target = device ?: return
        reconnectJob = scope.launch {
            var backoff = RECONNECT_BASE_DELAY_MS
            for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
                log(BleLogKind.SYS, "Перепідключення через $backoff мс (спроба $attempt/$MAX_RECONNECT_ATTEMPTS)")
                delay(backoff)
                val success = !interference || random.nextFloat() > RECONNECT_FAIL_PROBABILITY
                if (success) {
                    log(BleLogKind.SYS, "connectGatt(${target.address}) → STATE_CONNECTED")
                    discoverServices(target)
                    _connectionState.value = BleConnectionState.CONNECTED
                    startStatusNotifications()
                    return@launch
                }
                log(BleLogKind.ERR, "Спроба $attempt невдала")
                backoff *= 2
            }
            log(BleLogKind.ERR, "Зв'язок не відновлено — Disconnected")
            _connectionState.value = BleConnectionState.DISCONNECTED
            device = null
        }
    }

    private fun deliver(message: String) {
        log(BleLogKind.RX, "NOTIFY STATUS → $message")
        _notifications.tryEmit(message)
    }

    /** Пошкодження одного символу, як від радіоперешкоди. */
    private fun corrupt(frame: String): String {
        val i = random.nextInt(frame.length)
        val chars = frame.toCharArray()
        chars[i] = if (chars[i] == '#') '$' else '#'
        return String(chars)
    }

    private fun log(kind: BleLogKind, message: String) {
        _logs.tryEmit(BleLogEntry(kind, message))
    }

    companion object {
        const val CONNECT_DELAY_MS = 800L
        const val DISCOVERY_DELAY_MS = 400L
        const val LINK_LATENCY_MS = 60L
        const val ACK_TIMEOUT_MS = 300L
        const val MAX_ATTEMPTS = 3
        const val STATUS_PERIOD_MS = 5_000L
        const val RECONNECT_BASE_DELAY_MS = 500L
        const val MAX_RECONNECT_ATTEMPTS = 3

        const val PACKET_LOSS_PROBABILITY = 0.25f
        const val CORRUPTION_PROBABILITY = 0.10f
        const val LINK_DROP_PROBABILITY = 0.04f
        const val RECONNECT_FAIL_PROBABILITY = 0.3f
    }
}

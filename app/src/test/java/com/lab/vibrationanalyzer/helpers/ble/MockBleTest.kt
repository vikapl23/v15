package com.lab.vibrationanalyzer.helpers.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Тести Bluetooth Interface Layer. Працюють на звичайній JVM:
 * runTest використовує віртуальний час, тому delay(2000) не чекає 2 секунди.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MockBleTest {

    /** Random, що завжди повертає 0 — «перешкоди» спрацьовують гарантовано. */
    private object AlwaysZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    private fun TestScope.connector(
        hub: VirtualVibroHub = VirtualVibroHub(),
        random: Random = Random(15)
    ) = MockBleConnector(scope = backgroundScope, hub = hub, random = random)

    @Test
    fun scannerFindsVirtualDeviceAfterTwoSeconds() = runTest {
        val first = MockBleScanner().scan().first()
        assertEquals(2_000L, currentTime)
        assertEquals(VibroHubProfile.DEVICE_NAME, first.name)
        assertTrue(first.isSupported)
    }

    @Test
    fun scanFinishesAfterScanWindow() = runTest {
        val devices = MockBleScanner().scan().toList()
        assertEquals(4_000L, currentTime)
        assertEquals(2, devices.size)
        assertFalse(devices[1].isSupported)
    }

    @Test
    fun frameRoundTripAndChecksum() {
        val frame = VibroHubProfile.frame(7, "RELAY:OFF")
        val parsed = VibroHubProfile.parseFrame(frame)!!
        assertEquals(7, parsed.seq)
        assertEquals("RELAY:OFF", parsed.payload)
        assertTrue(parsed.checksumOk)

        val corrupted = frame.replace("OFF", "OFX")
        assertFalse(VibroHubProfile.parseFrame(corrupted)!!.checksumOk)
    }

    @Test
    fun telemetryGoesToTelemetryCharacteristic() {
        val packet = VibroHubProfile.telemetry(0.4234f, 1.1f, "ELEVATED", 3.25f)
        assertEquals("VIB:rms=0.42;pk=1.10;st=ELEVATED;wear=3.3", packet)
        assertEquals(VibroHubProfile.TELEMETRY_UUID, VibroHubProfile.characteristicFor(packet))
        assertEquals(VibroHubProfile.COMMAND_UUID, VibroHubProfile.characteristicFor("PING"))
    }

    @Test
    fun stateMachineConnectTransferDisconnect() = runTest {
        val c = connector()
        val states = mutableListOf<BleConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.connectionState.collect { states += it }
        }

        assertTrue(c.connect(VibroHubProfile.virtualDevice).isSuccess)
        assertTrue(c.sendData(VibroHubProfile.CMD_PING).isSuccess)
        c.disconnect()

        assertEquals(
            listOf(
                BleConnectionState.DISCONNECTED,
                BleConnectionState.CONNECTING,
                BleConnectionState.CONNECTED,
                BleConnectionState.DATA_TRANSFER,
                BleConnectionState.CONNECTED,
                BleConnectionState.DISCONNECTED
            ),
            states
        )
    }

    @Test
    fun relayCommandIsAcknowledgedAndNotified() = runTest {
        val hub = VirtualVibroHub()
        val c = connector(hub)
        val notifications = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.notifications.collect { notifications += it }
        }

        c.connect(VibroHubProfile.virtualDevice)
        assertTrue(c.sendData(VibroHubProfile.CMD_RELAY_OFF).isSuccess)

        assertFalse(hub.relayOn)
        assertTrue("ACK:1" in notifications)
        val status = VibroHubProfile.parseStatus(notifications.last())
        assertNotNull(status)
        assertFalse(status!!.relayOn)
    }

    @Test
    fun sendWithoutConnectionFails() = runTest {
        assertTrue(connector().sendData("PING").isFailure)
    }

    @Test
    fun unsupportedDeviceIsRejected() = runTest {
        val c = connector()
        val band = BleDevice("Mi Smart Band 7", "E4:5F:01:A2:33:7C", -80, emptyList())
        assertTrue(c.connect(band).isFailure)
        assertEquals(BleConnectionState.DISCONNECTED, c.connectionState.value)
    }

    @Test
    fun unknownCommandIsRejectedWithNak() = runTest {
        val c = connector()
        c.connect(VibroHubProfile.virtualDevice)
        val result = c.sendData("SELF_DESTRUCT")
        assertTrue(result.isFailure)
        assertEquals(BleConnectionState.CONNECTED, c.connectionState.value)
    }

    @Test
    fun telemetryIsStoredOnHub() = runTest {
        val hub = VirtualVibroHub()
        val c = connector(hub)
        c.connect(VibroHubProfile.virtualDevice)
        val packet = VibroHubProfile.telemetry(0.2f, 0.5f, "NORMAL", 0f)
        assertTrue(c.sendData(packet).isSuccess)
        assertEquals(packet, hub.lastTelemetry)
    }

    @Test
    fun retriesKeepDeliveryHighUnderInterference() = runTest {
        val hub = VirtualVibroHub()
        val c = connector(hub, Random(2026))
        val logs = mutableListOf<BleLogEntry>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.logs.collect { logs += it } }
        c.connect(VibroHubProfile.virtualDevice)
        c.interference = true

        var attempted = 0
        var delivered = 0
        repeat(100) {
            if (c.connectionState.value != BleConnectionState.CONNECTED) {
                advanceTimeBy(4_000)   // даємо час на перепідключення
            }
            if (c.connectionState.value == BleConnectionState.CONNECTED) {
                attempted++
                if (c.sendData(VibroHubProfile.telemetry(0.1f, 0.2f, "NORMAL", 0f)).isSuccess) delivered++
            }
        }

        // Без повторів доставлялося б ≈ 65 % (втрата 25 % + пошкодження 10 %)
        assertTrue("delivered $delivered of $attempted", delivered >= attempted * 0.85)
        assertTrue(logs.any { "повтор" in it.message })
        assertEquals(delivered, hub.packetsReceived)
    }

    @Test
    fun linkLossTriggersReconnect() = runTest {
        val c = connector(random = AlwaysZeroRandom)
        c.connect(VibroHubProfile.virtualDevice)
        c.interference = true

        assertTrue(c.sendData("PING").isFailure)
        assertEquals(BleConnectionState.RECONNECTING, c.connectionState.value)

        c.interference = false   // перешкоди зникли
        advanceTimeBy(2_000)
        assertEquals(BleConnectionState.CONNECTED, c.connectionState.value)
        assertTrue(c.sendData("PING").isSuccess)
    }

    @Test
    fun reconnectGivesUpAfterThreeAttempts() = runTest {
        val c = connector(random = AlwaysZeroRandom)
        c.connect(VibroHubProfile.virtualDevice)
        c.interference = true
        c.sendData("PING")

        advanceTimeBy(500L + 1_000L + 2_000L + 100L)   // 0.5 + 1 + 2 с експоненційної затримки
        assertEquals(BleConnectionState.DISCONNECTED, c.connectionState.value)
    }

    @Test
    fun hubNotifiesStatusPeriodically() = runTest {
        val c = connector()
        val notifications = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.notifications.collect { notifications += it }
        }
        c.connect(VibroHubProfile.virtualDevice)
        val before = notifications.size
        advanceTimeBy(MockBleConnector.STATUS_PERIOD_MS * 3 + 1)
        assertEquals(before + 3, notifications.size)
        assertTrue(notifications.all { it.startsWith("STATUS:") })
    }
}

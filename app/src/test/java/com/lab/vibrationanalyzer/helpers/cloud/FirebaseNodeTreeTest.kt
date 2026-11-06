package com.lab.vibrationanalyzer.helpers.cloud

import com.google.gson.JsonParser
import com.lab.vibrationanalyzer.repository.ControlCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ЛР4. Розбір подій потоку Firebase (put / patch) у команду. */
class FirebaseNodeTreeTest {

    private val tree = FirebaseNodeTree()

    @Test
    fun emptyBranchMeansNoCommands() {
        tree.apply("put", """{"path":"/","data":null}""")
        assertEquals(ControlCommand(), tree.command())
    }

    @Test
    fun initialPutWithWholeBranch() {
        tree.apply("put", """{"path":"/","data":{"led_status":true,"alarm":false,"threshold":2.5}}""")
        val c = tree.command()
        assertTrue(c.ledStatus)
        assertFalse(c.alarm)
        assertTrue(c.autoEnabled)           // ключа немає → за замовчуванням true
        assertEquals(2.5f, c.threshold!!, 1e-6f)
    }

    @Test
    fun putOfOneKeyFromConsole() {
        tree.apply("put", """{"path":"/","data":{"led_status":false,"alarm":false}}""")
        // студент змінив led_status у консолі Firebase
        assertTrue(tree.apply("put", """{"path":"/led_status","data":true}"""))
        assertTrue(tree.command().ledStatus)
        assertFalse(tree.command().alarm)
    }

    @Test
    fun patchMergesKeys() {
        tree.apply("put", """{"path":"/","data":{"led_status":true}}""")
        tree.apply("patch", """{"path":"/","data":{"alarm":true,"auto_enabled":false}}""")
        val c = tree.command()
        assertTrue(c.ledStatus)
        assertTrue(c.alarm)
        assertFalse(c.autoEnabled)
    }

    @Test
    fun deletingKeyRestoresDefault() {
        tree.apply("put", """{"path":"/","data":{"led_status":true,"threshold":1}}""")
        tree.apply("put", """{"path":"/threshold","data":null}""")
        assertNull(tree.command().threshold)
        assertTrue(tree.command().ledStatus)
    }

    @Test
    fun sameValueIsNotAChange() {
        tree.apply("put", """{"path":"/","data":{"led_status":true}}""")
        assertFalse(tree.apply("put", """{"path":"/led_status","data":true}"""))
    }

    @Test
    fun lenientValuesFromConsole() {
        val c = JsonParser.parseString("""{"led_status":"on","alarm":1,"auto_enabled":"false","threshold":"1,8"}""")
            .toControlCommand()
        assertTrue(c.ledStatus)
        assertTrue(c.alarm)
        assertFalse(c.autoEnabled)
        assertEquals(1.8f, c.threshold!!, 1e-6f)
    }

    @Test
    fun unknownEventIgnored() {
        assertFalse(tree.apply("keep-alive", """{"path":"/","data":null}"""))
    }
}

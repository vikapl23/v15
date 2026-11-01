package com.lab.vibrationanalyzer.helpers.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/** ЛР3. Адреса з local.properties → куди саме піде POST. */
class CloudConfigTest {

    @Test
    fun firebaseUrlGivesReadingsPath() {
        val c = CloudConfig.from(
            "https://smart-vibro-15-default-rtdb.europe-west1.firebasedatabase.app/", "", "vibro-15"
        )
        assertEquals(CloudConfig.Mode.FIREBASE, c.mode)
        assertEquals("https://smart-vibro-15-default-rtdb.europe-west1.firebasedatabase.app/", c.baseUrl)
        assertEquals("devices/vibro-15/readings.json", c.path)
        assertEquals(emptyMap<String, String>(), c.queryParams)
    }

    @Test
    fun oldFirebaseDomainWithoutSlashAndAuth() {
        val c = CloudConfig.from("https://my-lab.firebaseio.com", "secret", "")
        assertEquals(CloudConfig.Mode.FIREBASE, c.mode)
        assertEquals("https://my-lab.firebaseio.com/", c.baseUrl)
        assertEquals("devices/vibro-15/readings.json", c.path)
        assertEquals(mapOf("auth" to "secret"), c.queryParams)
    }

    @Test
    fun emptyUrlFallsBackToJsonPlaceholder() {
        val c = CloudConfig.from("", "", "vibro-15")
        assertEquals(CloudConfig.Mode.MOCK_REST, c.mode)
        assertEquals("https://jsonplaceholder.typicode.com/", c.baseUrl)
        assertEquals("posts", c.path)
    }

    @Test
    fun customRestUrlIsUsedAsIs() {
        val c = CloudConfig.from("https://run.mocky.io/v3/abc-123", "", "vibro-15")
        assertEquals(CloudConfig.Mode.CUSTOM_REST, c.mode)
        assertEquals("https://run.mocky.io/", c.baseUrl)
        assertEquals("v3/abc-123", c.path)
    }
}

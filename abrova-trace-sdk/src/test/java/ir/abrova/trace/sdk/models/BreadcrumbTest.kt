package ir.abrova.trace.sdk.models

import org.junit.Assert.*
import org.junit.Test

class BreadcrumbTest {

    // --- Construction ---

    @Test
    fun `breadcrumb created with defaults`() {
        val bc = Breadcrumb(message = "test message")
        assertEquals("test message", bc.message)
        assertEquals(BreadcrumbType.INFO, bc.type)
        assertNotNull(bc.timestamp)
        assertTrue(bc.data.isEmpty())
    }

    @Test
    fun `breadcrumb created with all fields`() {
        val data = mapOf("key" to "value")
        val bc = Breadcrumb(
            message = "test",
            type = BreadcrumbType.ERROR,
            data = data
        )
        assertEquals("test", bc.message)
        assertEquals(BreadcrumbType.ERROR, bc.type)
        assertEquals(data, bc.data)
    }

    // --- toMap ---

    @Test
    fun `toMap contains all fields`() {
        val data = mapOf("foo" to "bar")
        val bc = Breadcrumb(message = "test", type = BreadcrumbType.HTTP, data = data)
        val map = bc.toMap()

        assertEquals("test", map["message"])
        assertEquals("http", map["type"])
        assertEquals(bc.timestamp.time, map["timestamp"])
        assertEquals(data, map["data"])
    }

    @Test
    fun `toMap type uses enum value string`() {
        for (type in BreadcrumbType.values()) {
            val bc = Breadcrumb(message = "m", type = type)
            assertEquals(type.value, bc.toMap()["type"])
        }
    }

    // --- Navigation Factory ---

    @Test
    fun `navigation with from and to`() {
        val bc = Breadcrumb.navigation(from = "Home", to = "Settings")
        assertEquals("Navigation: Home -> Settings", bc.message)
        assertEquals(BreadcrumbType.NAVIGATION, bc.type)
        assertEquals("Home", bc.data["from"])
        assertEquals("Settings", bc.data["to"])
    }

    @Test
    fun `navigation with null from`() {
        val bc = Breadcrumb.navigation(from = null, to = "Main")
        assertEquals("Navigation: -> Main", bc.message)
        assertEquals(BreadcrumbType.NAVIGATION, bc.type)
        assertNull(bc.data["from"])
        assertEquals("Main", bc.data["to"])
    }

    // --- User Factory ---

    @Test
    fun `user breadcrumb with action only`() {
        val bc = Breadcrumb.user("Button clicked")
        assertEquals("Button clicked", bc.message)
        assertEquals(BreadcrumbType.USER, bc.type)
        assertTrue(bc.data.isEmpty())
    }

    @Test
    fun `user breadcrumb with data`() {
        val data = mapOf("button_id" to "submit_btn")
        val bc = Breadcrumb.user("Button clicked", data)
        assertEquals(data, bc.data)
    }

    // --- HTTP Factory ---

    @Test
    fun `http breadcrumb with status code`() {
        val bc = Breadcrumb.http("GET", "https://api.example.com/users", 200, 150L)
        assertEquals("HTTP GET https://api.example.com/users (200)", bc.message)
        assertEquals(BreadcrumbType.HTTP, bc.type)
        assertEquals("GET", bc.data["method"])
        assertEquals("https://api.example.com/users", bc.data["url"])
        assertEquals(200, bc.data["status_code"])
        assertEquals(150L, bc.data["duration_ms"])
    }

    @Test
    fun `http breadcrumb without status code`() {
        val bc = Breadcrumb.http("POST", "https://api.example.com/data")
        assertEquals("HTTP POST https://api.example.com/data", bc.message)
        assertEquals(BreadcrumbType.HTTP, bc.type)
        // null values are filtered out
        assertFalse(bc.data.containsKey("status_code"))
        assertFalse(bc.data.containsKey("duration_ms"))
    }

    @Test
    fun `http breadcrumb filters null values from data`() {
        val bc = Breadcrumb.http("GET", "https://example.com", null, null)
        assertFalse(bc.data.containsKey("status_code"))
        assertFalse(bc.data.containsKey("duration_ms"))
        assertEquals("GET", bc.data["method"])
        assertEquals("https://example.com", bc.data["url"])
    }

    // --- State Factory ---

    @Test
    fun `state breadcrumb`() {
        val data = mapOf("old" to "logged_out", "new" to "logged_in")
        val bc = Breadcrumb.state("Auth state changed", data)
        assertEquals("Auth state changed", bc.message)
        assertEquals(BreadcrumbType.STATE, bc.type)
        assertEquals(data, bc.data)
    }

    // --- System Factory ---

    @Test
    fun `system breadcrumb`() {
        val bc = Breadcrumb.system("Low memory warning")
        assertEquals("Low memory warning", bc.message)
        assertEquals(BreadcrumbType.SYSTEM, bc.type)
    }

    // --- Error Factory ---

    @Test
    fun `error breadcrumb`() {
        val data = mapOf("error_code" to 42)
        val bc = Breadcrumb.error("Something failed", data)
        assertEquals("Something failed", bc.message)
        assertEquals(BreadcrumbType.ERROR, bc.type)
        assertEquals(data, bc.data)
    }
}

class BreadcrumbTypeTest {

    @Test
    fun `enum values have correct string values`() {
        assertEquals("navigation", BreadcrumbType.NAVIGATION.value)
        assertEquals("user", BreadcrumbType.USER.value)
        assertEquals("http", BreadcrumbType.HTTP.value)
        assertEquals("state", BreadcrumbType.STATE.value)
        assertEquals("info", BreadcrumbType.INFO.value)
        assertEquals("error", BreadcrumbType.ERROR.value)
        assertEquals("system", BreadcrumbType.SYSTEM.value)
    }

    @Test
    fun `fromString returns correct type`() {
        assertEquals(BreadcrumbType.NAVIGATION, BreadcrumbType.fromString("navigation"))
        assertEquals(BreadcrumbType.USER, BreadcrumbType.fromString("user"))
        assertEquals(BreadcrumbType.HTTP, BreadcrumbType.fromString("http"))
        assertEquals(BreadcrumbType.STATE, BreadcrumbType.fromString("state"))
        assertEquals(BreadcrumbType.INFO, BreadcrumbType.fromString("info"))
        assertEquals(BreadcrumbType.ERROR, BreadcrumbType.fromString("error"))
        assertEquals(BreadcrumbType.SYSTEM, BreadcrumbType.fromString("system"))
    }

    @Test
    fun `fromString is case insensitive`() {
        assertEquals(BreadcrumbType.NAVIGATION, BreadcrumbType.fromString("NAVIGATION"))
        assertEquals(BreadcrumbType.HTTP, BreadcrumbType.fromString("Http"))
        assertEquals(BreadcrumbType.ERROR, BreadcrumbType.fromString("ERROR"))
    }

    @Test
    fun `fromString defaults to INFO for unknown values`() {
        assertEquals(BreadcrumbType.INFO, BreadcrumbType.fromString("unknown"))
        assertEquals(BreadcrumbType.INFO, BreadcrumbType.fromString(""))
        assertEquals(BreadcrumbType.INFO, BreadcrumbType.fromString("random_type"))
    }
}

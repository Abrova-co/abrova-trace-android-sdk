package ir.abrova.trace.sdk.services

import ir.abrova.trace.sdk.models.Breadcrumb
import ir.abrova.trace.sdk.models.BreadcrumbType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BreadcrumbServiceTest {

    @Before
    fun setUp() {
        BreadcrumbService.clear()
        BreadcrumbService.setMaxBreadcrumbs(20)
    }

    @After
    fun tearDown() {
        BreadcrumbService.clear()
    }

    // --- Basic Operations ---

    @Test
    fun `initially empty`() {
        assertEquals(0, BreadcrumbService.size())
        assertTrue(BreadcrumbService.getBreadcrumbs().isEmpty())
    }

    @Test
    fun `add breadcrumb object`() {
        val bc = Breadcrumb(message = "test breadcrumb")
        BreadcrumbService.add(bc)

        assertEquals(1, BreadcrumbService.size())
        assertEquals("test breadcrumb", BreadcrumbService.getBreadcrumbs()[0].message)
    }

    @Test
    fun `add breadcrumb with message`() {
        BreadcrumbService.add("simple message")

        assertEquals(1, BreadcrumbService.size())
        assertEquals("simple message", BreadcrumbService.getBreadcrumbs()[0].message)
        assertEquals(BreadcrumbType.INFO, BreadcrumbService.getBreadcrumbs()[0].type)
    }

    @Test
    fun `add breadcrumb with message and type`() {
        BreadcrumbService.add("error occurred", BreadcrumbType.ERROR)

        assertEquals(1, BreadcrumbService.size())
        assertEquals(BreadcrumbType.ERROR, BreadcrumbService.getBreadcrumbs()[0].type)
    }

    @Test
    fun `add breadcrumb with message type and data`() {
        val data = mapOf("detail" to "more info")
        BreadcrumbService.add("state change", BreadcrumbType.STATE, data)

        val bc = BreadcrumbService.getBreadcrumbs()[0]
        assertEquals("state change", bc.message)
        assertEquals(BreadcrumbType.STATE, bc.type)
        assertEquals(data, bc.data)
    }

    // --- Convenience Methods ---

    @Test
    fun `addNavigation creates navigation breadcrumb`() {
        BreadcrumbService.addNavigation("Home", "Settings")

        val bc = BreadcrumbService.getBreadcrumbs()[0]
        assertEquals(BreadcrumbType.NAVIGATION, bc.type)
        assertTrue(bc.message.contains("Home"))
        assertTrue(bc.message.contains("Settings"))
    }

    @Test
    fun `addNavigation with null from`() {
        BreadcrumbService.addNavigation(null, "Main")

        val bc = BreadcrumbService.getBreadcrumbs()[0]
        assertEquals(BreadcrumbType.NAVIGATION, bc.type)
        assertTrue(bc.message.contains("Main"))
    }

    @Test
    fun `addUser creates user breadcrumb`() {
        BreadcrumbService.addUser("Button clicked", mapOf("button" to "submit"))

        val bc = BreadcrumbService.getBreadcrumbs()[0]
        assertEquals(BreadcrumbType.USER, bc.type)
        assertEquals("Button clicked", bc.message)
        assertEquals("submit", bc.data["button"])
    }

    @Test
    fun `addHttp creates http breadcrumb`() {
        BreadcrumbService.addHttp("GET", "https://api.example.com", 200, 150L)

        val bc = BreadcrumbService.getBreadcrumbs()[0]
        assertEquals(BreadcrumbType.HTTP, bc.type)
        assertTrue(bc.message.contains("GET"))
        assertTrue(bc.message.contains("200"))
    }

    @Test
    fun `addHttp without optional params`() {
        BreadcrumbService.addHttp("POST", "https://api.example.com")

        val bc = BreadcrumbService.getBreadcrumbs()[0]
        assertEquals(BreadcrumbType.HTTP, bc.type)
    }

    @Test
    fun `addState creates state breadcrumb`() {
        BreadcrumbService.addState("Auth state changed")

        val bc = BreadcrumbService.getBreadcrumbs()[0]
        assertEquals(BreadcrumbType.STATE, bc.type)
        assertEquals("Auth state changed", bc.message)
    }

    @Test
    fun `addSystem creates system breadcrumb`() {
        BreadcrumbService.addSystem("Low memory")

        val bc = BreadcrumbService.getBreadcrumbs()[0]
        assertEquals(BreadcrumbType.SYSTEM, bc.type)
        assertEquals("Low memory", bc.message)
    }

    @Test
    fun `addError creates error breadcrumb`() {
        BreadcrumbService.addError("Something failed", mapOf("code" to 500))

        val bc = BreadcrumbService.getBreadcrumbs()[0]
        assertEquals(BreadcrumbType.ERROR, bc.type)
        assertEquals("Something failed", bc.message)
        assertEquals(500, bc.data["code"])
    }

    // --- Max Breadcrumbs & Trimming ---

    @Test
    fun `respects max breadcrumbs limit`() {
        BreadcrumbService.setMaxBreadcrumbs(5)

        for (i in 1..10) {
            BreadcrumbService.add("message $i")
        }

        assertEquals(5, BreadcrumbService.size())
    }

    @Test
    fun `trims oldest breadcrumbs when exceeding max`() {
        BreadcrumbService.setMaxBreadcrumbs(3)

        BreadcrumbService.add("first")
        BreadcrumbService.add("second")
        BreadcrumbService.add("third")
        BreadcrumbService.add("fourth")
        BreadcrumbService.add("fifth")

        val breadcrumbs = BreadcrumbService.getBreadcrumbs()
        assertEquals(3, breadcrumbs.size)
        assertEquals("third", breadcrumbs[0].message)
        assertEquals("fourth", breadcrumbs[1].message)
        assertEquals("fifth", breadcrumbs[2].message)
    }

    @Test
    fun `setMaxBreadcrumbs trims existing breadcrumbs`() {
        for (i in 1..10) {
            BreadcrumbService.add("message $i")
        }
        assertEquals(10, BreadcrumbService.size())

        BreadcrumbService.setMaxBreadcrumbs(3)
        assertEquals(3, BreadcrumbService.size())

        val breadcrumbs = BreadcrumbService.getBreadcrumbs()
        assertEquals("message 8", breadcrumbs[0].message)
        assertEquals("message 9", breadcrumbs[1].message)
        assertEquals("message 10", breadcrumbs[2].message)
    }

    @Test
    fun `max breadcrumbs of 1`() {
        BreadcrumbService.setMaxBreadcrumbs(1)

        BreadcrumbService.add("first")
        BreadcrumbService.add("second")

        assertEquals(1, BreadcrumbService.size())
        assertEquals("second", BreadcrumbService.getBreadcrumbs()[0].message)
    }

    // --- Clear ---

    @Test
    fun `clear removes all breadcrumbs`() {
        BreadcrumbService.add("one")
        BreadcrumbService.add("two")
        assertEquals(2, BreadcrumbService.size())

        BreadcrumbService.clear()
        assertEquals(0, BreadcrumbService.size())
        assertTrue(BreadcrumbService.getBreadcrumbs().isEmpty())
    }

    // --- getBreadcrumbsAsMap ---

    @Test
    fun `getBreadcrumbsAsMap returns list of maps`() {
        BreadcrumbService.add("test message", BreadcrumbType.INFO)

        val maps = BreadcrumbService.getBreadcrumbsAsMap()
        assertEquals(1, maps.size)

        val map = maps[0]
        assertEquals("test message", map["message"])
        assertEquals("info", map["type"])
        assertNotNull(map["timestamp"])
    }

    @Test
    fun `getBreadcrumbsAsMap preserves order`() {
        BreadcrumbService.add("first")
        BreadcrumbService.add("second")
        BreadcrumbService.add("third")

        val maps = BreadcrumbService.getBreadcrumbsAsMap()
        assertEquals("first", maps[0]["message"])
        assertEquals("second", maps[1]["message"])
        assertEquals("third", maps[2]["message"])
    }

    @Test
    fun `getBreadcrumbsAsMap returns empty list when no breadcrumbs`() {
        val maps = BreadcrumbService.getBreadcrumbsAsMap()
        assertTrue(maps.isEmpty())
    }

    // --- getBreadcrumbs returns copy ---

    @Test
    fun `getBreadcrumbs returns independent list`() {
        BreadcrumbService.add("original")
        val list = BreadcrumbService.getBreadcrumbs()

        BreadcrumbService.add("new one")

        // Original list should not be affected
        assertEquals(1, list.size)
        assertEquals(2, BreadcrumbService.size())
    }

    // --- Multiple Types ---

    @Test
    fun `supports mixed breadcrumb types`() {
        BreadcrumbService.addNavigation("A", "B")
        BreadcrumbService.addUser("click")
        BreadcrumbService.addHttp("GET", "https://example.com", 200)
        BreadcrumbService.addState("changed")
        BreadcrumbService.addSystem("system event")
        BreadcrumbService.addError("error occurred")

        assertEquals(6, BreadcrumbService.size())

        val types = BreadcrumbService.getBreadcrumbs().map { it.type }
        assertEquals(BreadcrumbType.NAVIGATION, types[0])
        assertEquals(BreadcrumbType.USER, types[1])
        assertEquals(BreadcrumbType.HTTP, types[2])
        assertEquals(BreadcrumbType.STATE, types[3])
        assertEquals(BreadcrumbType.SYSTEM, types[4])
        assertEquals(BreadcrumbType.ERROR, types[5])
    }
}

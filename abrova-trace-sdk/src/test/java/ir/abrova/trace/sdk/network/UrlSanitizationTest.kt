package ir.abrova.trace.sdk.network

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for URL sanitization logic used in interceptors.
 * Extracted as a standalone utility test since interceptors depend on OkHttp chain.
 */
class UrlSanitizationTest {

    /**
     * Replicates the sanitization logic from AbrovaTraceInterceptor
     * to enable testing without OkHttp chain dependency.
     */
    private fun sanitizeUrl(url: String): String {
        val sensitiveParams = listOf(
            "token", "api_key", "apikey", "key", "secret",
            "password", "pwd", "auth", "authorization",
            "access_token", "refresh_token", "session"
        )

        var sanitized = url
        for (param in sensitiveParams) {
            sanitized = sanitized.replace(
                Regex("([?&])$param=[^&]*", RegexOption.IGNORE_CASE),
                "$1$param=[REDACTED]"
            )
        }
        return sanitized
    }

    // --- No Sensitive Params ---

    @Test
    fun `url without query params is unchanged`() {
        val url = "https://api.example.com/users"
        assertEquals(url, sanitizeUrl(url))
    }

    @Test
    fun `url with non-sensitive params is unchanged`() {
        val url = "https://api.example.com/users?page=1&limit=20"
        assertEquals(url, sanitizeUrl(url))
    }

    // --- Single Sensitive Param ---

    @Test
    fun `redacts token param`() {
        val result = sanitizeUrl("https://example.com?token=secret123")
        assertEquals("https://example.com?token=[REDACTED]", result)
    }

    @Test
    fun `redacts api_key param`() {
        val result = sanitizeUrl("https://example.com?api_key=mykey")
        assertEquals("https://example.com?api_key=[REDACTED]", result)
    }

    @Test
    fun `redacts apikey param`() {
        val result = sanitizeUrl("https://example.com?apikey=mykey")
        assertEquals("https://example.com?apikey=[REDACTED]", result)
    }

    @Test
    fun `redacts key param`() {
        val result = sanitizeUrl("https://example.com?key=abc123")
        assertEquals("https://example.com?key=[REDACTED]", result)
    }

    @Test
    fun `redacts secret param`() {
        val result = sanitizeUrl("https://example.com?secret=mysecret")
        assertEquals("https://example.com?secret=[REDACTED]", result)
    }

    @Test
    fun `redacts password param`() {
        val result = sanitizeUrl("https://example.com?password=p@ss")
        assertEquals("https://example.com?password=[REDACTED]", result)
    }

    @Test
    fun `redacts pwd param`() {
        val result = sanitizeUrl("https://example.com?pwd=mypass")
        assertEquals("https://example.com?pwd=[REDACTED]", result)
    }

    @Test
    fun `redacts auth param`() {
        val result = sanitizeUrl("https://example.com?auth=bearer_token")
        assertEquals("https://example.com?auth=[REDACTED]", result)
    }

    @Test
    fun `redacts authorization param`() {
        val result = sanitizeUrl("https://example.com?authorization=Bearer+xyz")
        assertEquals("https://example.com?authorization=[REDACTED]", result)
    }

    @Test
    fun `redacts access_token param`() {
        val result = sanitizeUrl("https://example.com?access_token=at123")
        assertEquals("https://example.com?access_token=[REDACTED]", result)
    }

    @Test
    fun `redacts refresh_token param`() {
        val result = sanitizeUrl("https://example.com?refresh_token=rt456")
        assertEquals("https://example.com?refresh_token=[REDACTED]", result)
    }

    @Test
    fun `redacts session param`() {
        val result = sanitizeUrl("https://example.com?session=sess789")
        assertEquals("https://example.com?session=[REDACTED]", result)
    }

    // --- Case Insensitive ---

    @Test
    fun `redaction is case insensitive`() {
        val result = sanitizeUrl("https://example.com?TOKEN=secret&API_KEY=key")
        // The regex match is case-insensitive, replacement uses the pattern's lowercase name
        assertTrue(result.contains("[REDACTED]"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("=key"))
    }

    // --- Multiple Params ---

    @Test
    fun `redacts multiple sensitive params`() {
        val url = "https://example.com?token=abc&password=pass&page=1"
        val result = sanitizeUrl(url)
        assertTrue(result.contains("token=[REDACTED]"))
        assertTrue(result.contains("password=[REDACTED]"))
        assertTrue(result.contains("page=1"))
    }

    @Test
    fun `redacts sensitive param mixed with non-sensitive`() {
        val url = "https://example.com?user=john&api_key=secret&format=json"
        val result = sanitizeUrl(url)
        assertTrue(result.contains("user=john"))
        assertTrue(result.contains("api_key=[REDACTED]"))
        assertTrue(result.contains("format=json"))
    }

    // --- Sensitive as Second Param (with &) ---

    @Test
    fun `redacts sensitive param after ampersand`() {
        val url = "https://example.com?page=1&secret=hidden"
        val result = sanitizeUrl(url)
        assertEquals("https://example.com?page=1&secret=[REDACTED]", result)
    }

    // --- Edge Cases ---

    @Test
    fun `handles empty query value`() {
        val url = "https://example.com?token="
        val result = sanitizeUrl(url)
        assertEquals("https://example.com?token=[REDACTED]", result)
    }

    @Test
    fun `handles url with fragment`() {
        val url = "https://example.com?token=abc#section"
        val result = sanitizeUrl(url)
        assertTrue(result.contains("token=[REDACTED]"))
    }

    @Test
    fun `plain url without scheme`() {
        val url = "example.com?token=secret"
        val result = sanitizeUrl(url)
        assertEquals("example.com?token=[REDACTED]", result)
    }
}

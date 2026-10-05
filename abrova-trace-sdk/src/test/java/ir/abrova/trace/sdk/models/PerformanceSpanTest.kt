package ir.abrova.trace.sdk.models

import org.junit.Assert.*
import org.junit.Test

class PerformanceSpanTest {

    // --- Construction ---

    @Test
    fun `span created with required fields`() {
        val span = PerformanceSpan(
            operation = "GET /api/users",
            durationMs = 150,
            startTime = 1000L
        )
        assertEquals("GET /api/users", span.operation)
        assertEquals("http", span.operationType)
        assertEquals(150L, span.durationMs)
        assertEquals(1000L, span.startTime)
        assertEquals(1150L, span.endTime) // startTime + durationMs
        assertEquals("android", span.platform)
        assertEquals("ok", span.status)
    }

    @Test
    fun `endTime defaults to startTime plus durationMs`() {
        val span = PerformanceSpan(operation = "op", durationMs = 500, startTime = 2000)
        assertEquals(2500L, span.endTime)
    }

    @Test
    fun `endTime can be overridden`() {
        val span = PerformanceSpan(operation = "op", durationMs = 500, startTime = 2000, endTime = 3000)
        assertEquals(3000L, span.endTime)
    }

    // --- generateTraceId ---

    @Test
    fun `generateTraceId returns 32 char hex string`() {
        val traceId = PerformanceSpan.generateTraceId()
        assertEquals(32, traceId.length)
        assertTrue(traceId.matches(Regex("[a-f0-9]+")))
    }

    @Test
    fun `generateTraceId returns unique values`() {
        val ids = (1..100).map { PerformanceSpan.generateTraceId() }.toSet()
        assertEquals(100, ids.size)
    }

    // --- generateSpanId ---

    @Test
    fun `generateSpanId returns 16 char hex string`() {
        val spanId = PerformanceSpan.generateSpanId()
        assertEquals(16, spanId.length)
        assertTrue(spanId.matches(Regex("[a-f0-9]+")))
    }

    @Test
    fun `generateSpanId returns unique values`() {
        val ids = (1..100).map { PerformanceSpan.generateSpanId() }.toSet()
        assertEquals(100, ids.size)
    }

    // --- fromHttpRequest ---

    @Test
    fun `fromHttpRequest creates correct span for successful request`() {
        val span = PerformanceSpan.fromHttpRequest(
            method = "GET",
            url = "https://api.example.com/users",
            statusCode = 200,
            durationMs = 250,
            startTime = 1000L
        )

        assertEquals("GET /users", span.operation)
        assertEquals("http", span.operationType)
        assertEquals("GET", span.httpMethod)
        assertEquals("https://api.example.com/users", span.httpUrl)
        assertEquals(200, span.httpStatusCode)
        assertEquals("api.example.com", span.httpHost)
        assertEquals(250L, span.durationMs)
        assertEquals(1000L, span.startTime)
        assertEquals("ok", span.status)
        assertNotNull(span.traceId)
        assertNotNull(span.spanId)
    }

    @Test
    fun `fromHttpRequest sets error status for 4xx`() {
        val span = PerformanceSpan.fromHttpRequest(
            method = "POST",
            url = "https://api.example.com/auth",
            statusCode = 401,
            durationMs = 100,
            startTime = 1000L
        )
        assertEquals("error", span.status)
    }

    @Test
    fun `fromHttpRequest sets error status for 5xx`() {
        val span = PerformanceSpan.fromHttpRequest(
            method = "GET",
            url = "https://api.example.com/data",
            statusCode = 500,
            durationMs = 100,
            startTime = 1000L
        )
        assertEquals("error", span.status)
    }

    @Test
    fun `fromHttpRequest sets error status for null status code`() {
        val span = PerformanceSpan.fromHttpRequest(
            method = "GET",
            url = "https://api.example.com/data",
            statusCode = null,
            durationMs = 100,
            startTime = 1000L
        )
        assertEquals("error", span.status)
        assertNull(span.httpStatusCode)
    }

    @Test
    fun `fromHttpRequest sets ok status for 2xx`() {
        val span = PerformanceSpan.fromHttpRequest(
            method = "POST",
            url = "https://api.example.com/items",
            statusCode = 201,
            durationMs = 100,
            startTime = 1000L
        )
        assertEquals("ok", span.status)
    }

    @Test
    fun `fromHttpRequest sets ok status for 3xx`() {
        val span = PerformanceSpan.fromHttpRequest(
            method = "GET",
            url = "https://api.example.com/redirect",
            statusCode = 301,
            durationMs = 100,
            startTime = 1000L
        )
        assertEquals("ok", span.status)
    }

    @Test
    fun `fromHttpRequest uses provided traceId`() {
        val span = PerformanceSpan.fromHttpRequest(
            method = "GET",
            url = "https://example.com/test",
            statusCode = 200,
            durationMs = 100,
            startTime = 1000L,
            traceId = "custom_trace_id"
        )
        assertEquals("custom_trace_id", span.traceId)
    }

    @Test
    fun `fromHttpRequest generates traceId when not provided`() {
        val span = PerformanceSpan.fromHttpRequest(
            method = "GET",
            url = "https://example.com/test",
            statusCode = 200,
            durationMs = 100,
            startTime = 1000L
        )
        assertNotNull(span.traceId)
        assertEquals(32, span.traceId!!.length)
    }

    @Test
    fun `fromHttpRequest passes environment and release`() {
        val span = PerformanceSpan.fromHttpRequest(
            method = "GET",
            url = "https://example.com/test",
            statusCode = 200,
            durationMs = 100,
            startTime = 1000L,
            environment = "staging",
            releaseVersion = "1.0.0"
        )
        assertEquals("staging", span.environment)
        assertEquals("1.0.0", span.releaseVersion)
    }

    @Test
    fun `fromHttpRequest truncates long paths`() {
        val longPath = "/a".repeat(30) // 60 chars
        val span = PerformanceSpan.fromHttpRequest(
            method = "GET",
            url = "https://example.com$longPath",
            statusCode = 200,
            durationMs = 100,
            startTime = 1000L
        )
        // Operation should contain truncated path
        assertTrue(span.operation.length <= 60) // "GET " + 50 chars + "..."
    }

    // --- forDbQuery ---

    @Test
    fun `forDbQuery creates correct span`() {
        val span = PerformanceSpan.forDbQuery(
            queryType = "SELECT",
            tableName = "users",
            durationMs = 50,
            startTime = 1000L,
            rowsAffected = 10
        )

        assertEquals("SELECT users", span.operation)
        assertEquals("db", span.operationType)
        assertEquals("ok", span.status)
        assertEquals(50L, span.durationMs)
        assertEquals(1000L, span.startTime)
        assertEquals("users", span.tags["db_table"])
        assertEquals("SELECT", span.tags["query_type"])
        assertEquals(10, span.metadata["rows_affected"])
    }

    @Test
    fun `forDbQuery without rowsAffected`() {
        val span = PerformanceSpan.forDbQuery(
            queryType = "INSERT",
            tableName = "orders",
            durationMs = 30,
            startTime = 1000L
        )
        assertFalse(span.metadata.containsKey("rows_affected"))
    }

    @Test
    fun `forDbQuery merges custom metadata`() {
        val span = PerformanceSpan.forDbQuery(
            queryType = "UPDATE",
            tableName = "products",
            durationMs = 20,
            startTime = 1000L,
            rowsAffected = 5,
            metadata = mapOf("query" to "UPDATE products SET price = 10")
        )
        assertEquals("UPDATE products SET price = 10", span.metadata["query"])
        assertEquals(5, span.metadata["rows_affected"])
    }

    // --- custom ---

    @Test
    fun `custom creates correct span`() {
        val span = PerformanceSpan.custom(
            operation = "image_processing",
            durationMs = 1200,
            startTime = 1000L,
            operationType = "compute",
            status = "ok",
            tags = mapOf("format" to "png"),
            metadata = mapOf("width" to 1920, "height" to 1080)
        )

        assertEquals("image_processing", span.operation)
        assertEquals("compute", span.operationType)
        assertEquals(1200L, span.durationMs)
        assertEquals("ok", span.status)
        assertEquals("png", span.tags["format"])
        assertEquals(1920, span.metadata["width"])
        assertEquals(1080, span.metadata["height"])
    }

    @Test
    fun `custom with error status and message`() {
        val span = PerformanceSpan.custom(
            operation = "data_sync",
            durationMs = 5000,
            startTime = 1000L,
            status = "error",
            errorMessage = "Connection timeout"
        )
        assertEquals("error", span.status)
        assertEquals("Connection timeout", span.errorMessage)
    }

    @Test
    fun `custom defaults to custom operation type`() {
        val span = PerformanceSpan.custom(
            operation = "my_op",
            durationMs = 100,
            startTime = 1000L
        )
        assertEquals("custom", span.operationType)
    }

    // --- toMap ---

    @Test
    fun `toMap contains all non-null fields`() {
        val span = PerformanceSpan(
            operation = "GET /api",
            operationType = "http",
            traceId = "trace123",
            spanId = "span456",
            httpMethod = "GET",
            httpUrl = "https://example.com/api",
            httpStatusCode = 200,
            httpHost = "example.com",
            durationMs = 100,
            startTime = 1000L,
            environment = "production",
            releaseVersion = "1.0",
            status = "ok",
            tags = mapOf("key" to "val"),
            metadata = mapOf("meta" to "data")
        )
        val map = span.toMap()

        assertEquals("GET /api", map["operation"])
        assertEquals("http", map["operation_type"])
        assertEquals("trace123", map["trace_id"])
        assertEquals("span456", map["span_id"])
        assertEquals("GET", map["http_method"])
        assertEquals("https://example.com/api", map["http_url"])
        assertEquals(200, map["http_status_code"])
        assertEquals("example.com", map["http_host"])
        assertEquals(100L, map["duration_ms"])
        assertEquals("android", map["platform"])
        assertEquals("production", map["environment"])
        assertEquals("1.0", map["release_version"])
        assertEquals("ok", map["status"])
        assertEquals(mapOf("key" to "val"), map["tags"])
        assertEquals(mapOf("meta" to "data"), map["metadata"])
    }

    @Test
    fun `toMap filters null values`() {
        val span = PerformanceSpan(
            operation = "op",
            durationMs = 100,
            startTime = 1000L
        )
        val map = span.toMap()

        assertFalse(map.containsKey("trace_id"))
        assertFalse(map.containsKey("span_id"))
        assertFalse(map.containsKey("parent_span_id"))
        assertFalse(map.containsKey("http_method"))
        assertFalse(map.containsKey("http_url"))
        assertFalse(map.containsKey("http_status_code"))
        assertFalse(map.containsKey("http_host"))
        assertFalse(map.containsKey("environment"))
        assertFalse(map.containsKey("release_version"))
        assertFalse(map.containsKey("error_message"))
    }

    @Test
    fun `toMap excludes empty tags and metadata`() {
        val span = PerformanceSpan(
            operation = "op",
            durationMs = 100,
            startTime = 1000L,
            tags = emptyMap(),
            metadata = emptyMap()
        )
        val map = span.toMap()
        assertFalse(map.containsKey("tags"))
        assertFalse(map.containsKey("metadata"))
    }

    @Test
    fun `toMap includes ISO 8601 timestamps`() {
        val span = PerformanceSpan(operation = "op", durationMs = 100, startTime = 1000L)
        val map = span.toMap()
        val startTime = map["start_time"] as String
        val endTime = map["end_time"] as String
        assertTrue(startTime.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")))
        assertTrue(endTime.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")))
    }

    // --- fromHttpRequest host extraction ---

    @Test
    fun `fromHttpRequest extracts host from valid URL`() {
        val span = PerformanceSpan.fromHttpRequest(
            method = "GET",
            url = "https://api.example.com:8080/path",
            statusCode = 200,
            durationMs = 100,
            startTime = 1000L
        )
        assertEquals("api.example.com", span.httpHost)
    }

    @Test
    fun `fromHttpRequest handles invalid URL for host`() {
        val span = PerformanceSpan.fromHttpRequest(
            method = "GET",
            url = "not-a-valid-url",
            statusCode = 200,
            durationMs = 100,
            startTime = 1000L
        )
        assertNull(span.httpHost)
    }
}

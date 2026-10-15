package dev.nuaforma.contracts

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GenerationLoggingTest {
    @Test
    fun `production suppresses values and exceptions retain frames but never messages`() {
        val events = mutableListOf<GenerationLogEvent>()
        val log = GenerationLogging(LoggingMode.PRODUCTION, "test-1", events::add)
        log.trace("FIELD_RESOLVED", mapOf("value" to "PRIVATE_DOCUMENT"))
        log.failure("PROCESS_FAILED", IllegalStateException("Bearer TOKEN_PRIVATE", IllegalArgumentException("password=PRIVATE")))
        assertEquals(1, events.size)
        assertEquals(GenerationLogLevel.ERROR, events.single().level)
        val text = events.single().text
        assertTrue(text.contains("IllegalStateException")); assertTrue(text.contains("IllegalArgumentException"))
        assertTrue(text.contains("GenerationLoggingTest")); assertTrue(text.contains(" at "))
        assertFalse(text.contains("PRIVATE")); assertFalse(text.contains("Bearer"))
        assertEquals("test-1", events.single().requestId)
    }

    @Test
    fun `development escapes controls filters secret fields and omits binary and opaque objects`() {
        val events = mutableListOf<GenerationLogEvent>()
        val log = GenerationLogging(LoggingMode.DEVELOPMENT, null, events::add)
        val hostile = object { override fun toString(): String = error("must not execute") }
        val map = object : AbstractMap<String, Any?>() { override val entries: Set<Map.Entry<String, Any?>> get() = error("must not iterate") }
        log.trace("PIPE_RESULT", mapOf("value" to "Ada\r\n\u001b[31m\u202e", "nested" to mapOf("password" to "PRIVATE", "label" to "safe"),
            "image" to "A".repeat(8000), "binary" to byteArrayOf(1,2), "unknown" to hostile, "customMap" to map))
        val text = events.single().text
        assertTrue(text.contains("Ada\\u000d\\u000a")); assertTrue(text.contains("\\u001b")); assertTrue(text.contains("\\u202e"))
        assertTrue(text.contains("[redacted]")); assertFalse(text.contains("PRIVATE")); assertTrue(text.contains("safe"))
        assertTrue(text.contains("[payload omitted]")); assertTrue(text.contains("[opaque]"))
        assertEquals(GenerationLogLevel.DEBUG, events.single().level)
        log.trace("KNOWN_SECRET_VALUES", mapOf("first" to "Bearer PRIVATE_BEARER", "second" to "Basic PRIVATE_BASIC",
            "third" to "-----BEGIN PRIVATE KEY-----\nPRIVATE_KEY_MATERIAL"))
        assertFalse(events.last().text.contains("PRIVATE"))
    }

    @Test
    fun `event and cumulative limits are private bounded and logging callbacks cannot replace generation`() {
        val events = mutableListOf<GenerationLogEvent>()
        val log = GenerationLogging(LoggingMode.DEVELOPMENT, "bounded", events::add)
        repeat(10000) { log.trace("VALUE", mapOf("values" to List(12) { "界 ".repeat(1000) })) }
        assertTrue(events.size <= 256)
        assertTrue(events.all { it.text.toByteArray().size <= 4096 })
        assertTrue(events.sumOf { it.text.toByteArray().size } <= 131072)
        log.failure("RESERVED_FAILURE", IllegalStateException("withheld"))
        assertEquals("RESERVED_FAILURE", events.last().code)
        val next = GenerationLogging(LoggingMode.DEVELOPMENT, "next", events::add)
        next.trace("NEXT", mapOf("value" to "visible"))
        assertEquals("NEXT", events.last().code)
        assertDoesNotThrow { GenerationLogging(LoggingMode.PRODUCTION, null) { error("backend failed") }.operation("EVENT") }
        repeat(1000) { GenerationLogging.NONE.failure("NOOP", RuntimeException("private")) }
    }

    @Test
    fun `correlation identity is validated without echoing untrusted input`() {
        val failure = assertThrows(IllegalArgumentException::class.java) { GenerationLogging(requestId = "PRIVATE\nINJECT") }
        assertFalse(failure.message!!.contains("PRIVATE"))
        assertDoesNotThrow { GenerationContext(GenerationLogging()) }
        val defaults = GenerationContext::class.java.declaredConstructors.single { it.parameterCount == 6 }
        assertEquals(listOf(GenerationLimits::class.java, CancellationToken::class.java, java.time.Instant::class.java,
            java.time.Clock::class.java, Int::class.javaPrimitiveType, kotlin.jvm.internal.DefaultConstructorMarker::class.java), defaults.parameterTypes.toList())
    }
}

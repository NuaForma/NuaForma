package dev.nuaforma.template

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LoggingTest {
    private fun pipe(name: String, action: (Any?) -> Any?) = object : TemplatePipe {
        override val definition = TemplatePipeDefinition(name, "Synthetic conversion")
        override fun apply(value: Any?, arguments: List<Any?>, context: TemplatePipeContext): Any? = action(value)
    }

    @Test
    fun `explicit development follows field values custom chains builtin formatters and failures`() {
        val events = mutableListOf<GenerationLogEvent>()
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT, "trace-1", events::add))
        val pipes = TemplatePipes.of(listOf(pipe("upper") { (it as String).uppercase() }, pipe("fail") { throw IllegalArgumentException("PRIVATE_EXCEPTION") }))
        val text = Templates.compile("Hello {customer.name | upper()} {amount | number(\"0.00\")}", pipes, context)
        assertEquals("Hello ADA 3.00", text.render(mapOf("customer" to mapOf("name" to "Ada"), "amount" to 3), GenerationOptions(), context))
        assertTrue(events.any { it.code == "FIELD_RESOLVED" && it.text.contains("customer.name") && it.text.contains("Ada") })
        assertTrue(events.any { it.code == "PIPE_RESULT" && it.text.contains("upper") && it.text.contains("ADA") })
        assertTrue(events.any { it.text.contains("number") && it.text.contains("3.00") })
        val failure = assertThrows(GenerationException::class.java) {
            Templates.compile("{name | upper() | fail()}", pipes, context).render(mapOf("name" to "Ada"), GenerationOptions(), context)
        }
        assertTrue(events.any { it.code == "VALUE_PIPE_FAILED" && it.level == GenerationLogLevel.ERROR })
        assertFalse(events.any { it.text.contains("PRIVATE_EXCEPTION") })
        assertFalse(failure.message.orEmpty().contains("Ada"))
        assertTrue(events.all { it.requestId == "trace-1" })
    }

    @Test
    fun `production and known secret paths remain redacted across chains and aliases`() {
        val events = mutableListOf<GenerationLogEvent>()
        val pipes = TemplatePipes.of(listOf(pipe("echo") { it }))
        for (mode in LoggingMode.entries) {
            val context = GenerationContext(GenerationLogging(mode, "secrets", events::add))
            val text = Templates.compile("{password | echo()} {nested.apiToken | echo()}", pipes, context)
            assertEquals("PRIVATE_PASS PRIVATE_TOKEN", text.render(mapOf("password" to "PRIVATE_PASS", "nested" to mapOf("apiToken" to "PRIVATE_TOKEN")), GenerationOptions(), context))
            assertEquals("PRIVATE_ALIAS", Templates.compile("{#each credentials as item}{item.value | echo()}{/each}", pipes, context)
                .render(mapOf("credentials" to listOf(mapOf("value" to "PRIVATE_ALIAS"))), GenerationOptions(), context))
        }
        assertFalse(events.any { it.text.contains("PRIVATE_PASS") || it.text.contains("PRIVATE_TOKEN") || it.text.contains("PRIVATE_ALIAS") })
        assertTrue(events.all { it.development })
    }
}

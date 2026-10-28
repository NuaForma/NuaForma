package dev.nuaforma.template

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class QuotationAndSyntaxTest {
    private val quotes = "\"'‘’‚‛“”„‟«»‹›"
    private fun development() = GenerationContext(logging = GenerationLogging(LoggingMode.DEVELOPMENT))

    @Test
    fun `all opening and closing quote combinations work in formatting functions keys and conditions`() {
        for (open in quotes) for (close in quotes) {
            val source = "{day | date(${open}dd-MM-yyyy${close})} {1.5 | number(${open}0.00${close})} " +
                "{trim(${open} Ada ${close})} {data[${open}full name${close}]} " +
                "{#if name == ${open}Ada${close}}yes{/if} {absent ?? ${open}fallback${close}}"
            assertEquals("28-10-2026 1.50 Ada Lovelace yes fallback", Templates.compile(source).render(
                mapOf("day" to "2026-10-28", "full name" to "Lovelace", "name" to "Ada")), source)
        }
    }

    @Test
    fun `apostrophes quoted date literals escapes braces and pipes remain literal`() {
        val options = GenerationOptions(locale = Locale.forLanguageTag("nl-NL"), timeZone = ZoneId.of("Europe/Amsterdam"))
        for (open in quotes) for (close in quotes) {
            val source = "{moment | date(${open}dd MMMM yyyy 'om' HH:mm${close})}"
            assertEquals("28 oktober 2026 om 15:05", Templates.compile(source).render(
                mapOf("moment" to "2026-10-28T14:05:00Z"), options, GenerationContext()))
        }
        assertEquals("O’Brien's", Templates.compile("{\"O’Brien's\"}").render(emptyMap<String, Any?>()))
        assertEquals("a'b", Templates.compile("{'a\\'b'}").render(emptyMap<String, Any?>()))
        assertEquals("a\"b", Templates.compile("{\"a\\\"b\"}").render(emptyMap<String, Any?>()))
        assertEquals("{a|b}", Templates.compile("{‘{a|b}”}").render(emptyMap<String, Any?>()))
        assertEquals("{unknown()}", Templates.compile("{value}").render(mapOf("value" to "{unknown()}")))
        assertEquals("om 15:05", Templates.compile("{moment | date(“‘om’ HH:mm')}").render(
            mapOf("moment" to "2026-10-28T14:05:00Z"), options, GenerationContext()))
        assertEquals("28 om, 15:05", Templates.compile("{moment | date(“dd 'om', HH:mm')}").render(
            mapOf("moment" to "2026-10-28T14:05:00Z"), options, GenerationContext()))
    }

    @Test
    fun `content detection and custom arguments share the same quote rules`() {
        val valuePipe = object : TemplatePipe {
            override val definition = TemplatePipeDefinition("label", "Synthetic label", listOf(OptionDefinition("prefix", OptionType.STRING, "Prefix", required = true)))
            override fun apply(value: Any?, arguments: List<Any?>, context: TemplatePipeContext): Any? = "${arguments[0]}$value"
        }
        val contentPipe = object : ContentPipe {
            override val definition = ContentPipeDefinition("label", "Synthetic content", listOf(OptionDefinition("prefix", OptionType.STRING, "Prefix", required = true)))
            override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult = ContentResult.Text("${arguments[0]}$value")
        }
        for (open in quotes) for (close in quotes) {
            assertFalse(ContentTemplates.containsContentMarker("{${open}x | label()${close}}"))
            assertTrue(ContentTemplates.containsContentMarker("{x | label(${open}prefix${close})}"))
            assertEquals("prefixx", Templates.compile("{x | label(${open}prefix${close})}", TemplatePipes.of(listOf(valuePipe))).render(mapOf("x" to "x")))
            val content = ContentTemplates.open(mapOf("x" to "x"), listOf(contentPipe)).expandContent("{x | label(${open}prefix${close})}").single().content
            assertEquals("prefixx", (content as ContentResult.Text).text)
        }
    }

    @Test
    fun `syntax failures have a fixed explanation and precise development-only marker context`() {
        val cases = mapOf(
            "Prefix {trim(name,)} suffix" to (RejectionReason.UNEXPECTED_EXPRESSION_TOKEN to ')'),
            "Prefix {name | nope()} suffix" to (RejectionReason.INVALID_PIPE_EXPRESSION to 'n'),
            "Prefix {name | date(\"invalid\")} suffix" to (RejectionReason.INVALID_FORMAT_PATTERN to 'i'),
            "Prefix {unknown(name)} suffix" to (RejectionReason.UNSUPPORTED_EXPRESSION_FUNCTION to 'u'),
            "Prefix {trim(name} suffix" to (RejectionReason.EXPECTED_EXPRESSION_DELIMITER to '}'),
        )
        for ((source, expected) in cases) {
            val error = assertThrows(GenerationException::class.java) { Templates.compile(source, development()) }.diagnostics.single()
            assertEquals(expected.first, error.rejectionReason, source)
            val details = requireNotNull(error.templateContext)
            assertTrue(details.expression.startsWith("{"), details.expression)
            assertEquals(expected.second, details.expression[details.position], source)
            assertEquals(7, details.sourceOffset)
            assertFalse(error.message.contains(source))
            val production = assertThrows(GenerationException::class.java) { Templates.compile(source) }.diagnostics.single()
            assertNull(production.templateContext)
        }
        val unclosed = assertThrows(GenerationException::class.java) { Templates.compile("{name ?? ‘missing}", development()) }.diagnostics.single()
        assertEquals(RejectionReason.UNCLOSED_EXPRESSION_STRING, unclosed.rejectionReason)
        assertEquals('‘', unclosed.templateContext!!.expression[unclosed.templateContext!!.position])
        val direct = assertThrows(GenerationException::class.java) { TemplateSession.open(emptyMap<String, Any?>(), context = development()).validateExpression("trim(name,)") }.diagnostics.single()
        assertEquals("trim(name,)", direct.templateContext!!.expression)
    }

    @Test
    fun `excerpts stay bounded and concurrent request contexts never mix`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..32).map { number -> Callable {
                val source = "{trim(\"case$number\",)}"
                val error = assertThrows(GenerationException::class.java) { Templates.compile(source, development()) }.diagnostics.single()
                assertEquals(source, error.templateContext!!.expression)
            } }
            executor.invokeAll(jobs).forEach { it.get() }
        } finally { executor.shutdownNow() }
        val source = "{trim(\"${"x".repeat(400)}\",)}"
        val error = assertThrows(GenerationException::class.java) { Templates.compile(source, development()) }.diagnostics.single()
        val details = requireNotNull(error.templateContext)
        assertTrue(details.expression.length <= 256)
        assertTrue(details.position in 0..details.expression.length)
        assertTrue(details.excerptStart > 0)
    }
}

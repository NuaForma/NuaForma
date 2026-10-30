package dev.nuaforma.template

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class ContentPipesTest {
    @Test
    fun `missing values become null only for an opted-in content pipe and never for arguments or ordinary placeholders`() {
        var calls = 0
        val nullable = object : ContentPipe {
            override val definition = ContentPipeDefinition("nullable", "Missing-as-null content insertion", listOf(
                OptionDefinition("flag", OptionType.BOOLEAN, "Optional flag", defaultValue = true)), emptyList(),
                "Null inserts empty content", emptySet(), true)
            override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult {
                calls++; assertNull(value); return ContentResult.Empty
            }
        }
        val session = ContentTemplates.open(mapOf("explicit" to null), listOf(nullable, pipe()))
        assertSame(ContentResult.Empty, session.expandContent("{missing | nullable()}").single().content)
        assertSame(ContentResult.Empty, session.expandContent("{explicit | nullable()}").single().content)
        assertEquals(2, calls)
        for (source in listOf("{missing}", "{missing | label()}", "{explicit | nullable(missing)}")) {
            assertEquals(DiagnosticCodes.MISSING_VALUE, assertThrows(GenerationException::class.java) {
                session.expandContent(source)
            }.diagnostics.single().code)
        }
        assertEquals(2, calls)
        assertFalse(pipe().definition.missingAsNull)
        assertFalse(ContentPipeDefinition("legacy", "Legacy media-type constructor", emptyList(), emptyList(), "Strict missing", setOf("text/plain")).missingAsNull)
    }
    private fun pipe(action: (Any?, List<Any?>) -> ContentResult = { value, args -> ContentResult.Text("${args[0]}$value") }): ContentPipe =
        object : ContentPipe {
            override val definition = ContentPipeDefinition("label", "Synthetic inline content",
                listOf(OptionDefinition("prefix", OptionType.STRING, "Literal prefix", defaultValue = "")),
                listOf(ContentPipeSupport(DocumentFormat.DOCX, DocumentFormat.DOCX)))
            override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult {
                context.checkActive()
                return action(value, arguments)
            }
        }

    @Test
    fun `custom structured results are transported without stringification or interpretation`() {
        val result = object : ContentResult {
            override fun toString(): String = throw AssertionError("Structured content must not be stringified")
        }
        val session = ContentTemplates.open(mapOf("value" to "literal"), listOf(pipe { _, _ -> result }))
        assertSame(result, session.expandContent("{value | label()}").single().content)
    }

    @Test
    fun `typed defaults optional null and nested allowed values preserve JSON numeric semantics`() {
        val pipe = object : ContentPipe {
            override val definition = ContentPipeDefinition("defaults", "Synthetic typed defaults", listOf(
                OptionDefinition("count", OptionType.INTEGER, "Integer default", defaultValue = java.math.BigInteger("4")),
                OptionDefinition("mode", OptionType.STRING, "Optional enum", allowedValues = listOf("one")),
                OptionDefinition("object", OptionType.OBJECT, "Structured argument", allowedValues = listOf(mapOf("items" to listOf(1)))),
            ))
            override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult {
                if (arguments[2] == null) {
                    assertEquals(java.math.BigInteger("4"), arguments[0]); assertNull(arguments[1])
                } else assertEquals(listOf(java.math.BigDecimal("1.0")), (arguments[2] as Map<*, *>)["items"])
                return ContentResult.Empty
            }
        }
        val session = ContentTemplates.open(mapOf("value" to "x", "object" to mapOf("items" to listOf(java.math.BigDecimal("1.0")))), listOf(pipe))
        session.expandContent("{value | defaults()}")
        session.expandContent("{value | defaults(4, null, object)}")
        assertThrows(GenerationException::class.java) { session.expandContent("{value | defaults(4.5)}") }
    }

    @Test
    fun `non-insertable location detection ignores literal strings escaped markers and boolean operators`() {
        for (source in listOf("{{value | label()}}", "{\"value | label()\"}", "{true || false}", "{value | number(\"0\")}", "{value | date(\"yyyy\")}")) {
            assertFalse(ContentTemplates.containsContentMarker(source), source)
        }
        assertTrue(ContentTemplates.containsContentMarker("{value | unknown()}"))
        val session = ContentTemplates.open(mapOf("value" to "x"), listOf(pipe()))
        assertEquals("x", (session.expandContent("{value | label(\n)}").single().content as ContentResult.Text).text)
        val tooLong = ContentTemplates.open(mapOf("value" to "123456"), listOf(pipe()),
            context = GenerationContext(GenerationLimits(maxOutputBytes = 5)))
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED,
            assertThrows(GenerationException::class.java) { tooLong.expandContent("{value | label()}") }.diagnostics.single().code)
    }

    @Test
    fun `content resolves nested scopes expression arguments defaults and null without interpreting returned markers`() {
        val session = ContentTemplates.open(mapOf("groups" to listOf(mapOf("items" to listOf("{secret}", null))), "prefix" to "P"), listOf(pipe()))
        val fragments = session.expandContent("{#each groups as group}{#each group.items as item}{#if loop.first}{item | label(trim(prefix))}{#else}{item | label()}{/if}{/each}{/each}")
        assertEquals(listOf("P{secret}", "null"), fragments.map { (it.content as ContentResult.Text).text })
        assertEquals("fallback", (session.expandContent("{missing ?? null | label(\"fallback\")}").single().content as ContentResult.Text).text.removeSuffix("null"))
        val missing = assertThrows(GenerationException::class.java) { session.expandContent("{missing | label()}") }
        assertEquals(DiagnosticCodes.MISSING_VALUE, missing.diagnostics.single().code)
    }

    @Test
    fun `all preceding following and mixed chains fail even in dead branches before callbacks`() {
        var calls = 0
        val session = ContentTemplates.open(mapOf("value" to "secret"), listOf(pipe { _, _ -> calls++; ContentResult.Empty }))
        for (source in listOf("{value | label() | label()}", "{value | number(\"0\") | label()}",
            "{value | label() | number(\"0\")}", "{value | unknown()}", "{#if false}{value | label() | unknown()}{/if}")) {
            assertThrows(GenerationException::class.java, { session.expandContent(source) }, source)
        }
        assertEquals(0, calls)
        session.validate("{value | label(\"a|b\")}")
    }

    @Test
    fun `text APIs and watermarks reject content without invoking it`() {
        var calls = 0
        val session = ContentTemplates.open(mapOf("value" to "secret"), listOf(pipe { _, _ -> calls++; ContentResult.Empty }))
        assertThrows(GenerationException::class.java) { session.expand("{#if false}{value | label()}{/if}") }
        assertThrows(GenerationException::class.java) { session.validateText("{value | label()}") }
        assertThrows(GenerationException::class.java) { Templates.compile("{value | label()}") }
        assertEquals(0, calls)
    }

    @Test
    fun `duplicate registrations invalid argument types and implementation failures are deterministic and sanitized`() {
        assertThrows(GenerationException::class.java) { ContentTemplates.open(emptyMap<String, Any?>(), listOf(pipe(), pipe())) }
        val session = ContentTemplates.open(mapOf("secret" to "sensitive"), listOf(pipe { _, _ -> error("sensitive") }))
        for (source in listOf("{secret | label(true)}", "{secret | label(\"a\", \"b\")}", "{secret | label()}")) {
            val failure = assertThrows(GenerationException::class.java) { session.expandContent(source) }
            assertTrue(failure.diagnostics.all { "sensitive" !in it.message })
            assertNull(failure.cause)
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `extension cannot mutate nested input or arguments and defaults are padded`() {
        val supplied = mutableMapOf("nested" to mutableListOf("original"))
        val session = ContentTemplates.open(mapOf("value" to supplied), listOf(pipe { value, args ->
            assertEquals(listOf(""), args)
            assertThrows(UnsupportedOperationException::class.java) { (value as MutableMap<Any?, Any?>).clear() }
            assertThrows(UnsupportedOperationException::class.java) { ((value as Map<*, *>)["nested"] as MutableList<*>).clear() }
            ContentResult.Empty
        }))
        assertSame(ContentResult.Empty, session.expandContent("{value | label()}").single().content)
        assertEquals(listOf("original"), supplied["nested"])
    }

    @Test
    fun `output calls and cancellation budgets are cumulative across structural scopes`() {
        val context = GenerationContext(GenerationLimits(maxOutputBytes = 5, maxRepetitions = 1))
        val session = ContentTemplates.open(mapOf("value" to "abc"), listOf(pipe()), context = context)
        session.expandContent("{value | label()}")
        assertThrows(GenerationException::class.java) { session.branch().expandContent("{value | label()}") }
        val cancelled = GenerationContext(cancellation = CancellationToken { true })
        assertThrows(GenerationException::class.java) { ContentTemplates.open(emptyMap<String, Any?>(), listOf(pipe()), context = cancelled) }
        val signal = java.util.concurrent.atomic.AtomicBoolean(false)
        val during = ContentTemplates.open(mapOf("value" to "private"), listOf(pipe { _, _ ->
            signal.set(true); ContentResult.Text("must not be delivered")
        }), context = GenerationContext(cancellation = CancellationToken { signal.get() }))
        assertEquals(DiagnosticCodes.CANCELLED,
            assertThrows(GenerationException::class.java) { during.expandContent("{value | label()}") }.diagnostics.single().code)
    }

    @Test
    fun `concurrent sessions never share scopes or results`() {
        val implementation = pipe()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..20).map { number -> executor.submit<String> {
                val session = ContentTemplates.open(mapOf("value" to "request-$number"), listOf(implementation))
                (session.expandContent("{value | label()}").single().content as ContentResult.Text).text
            } }
            assertEquals((1..20).map { "request-$it" }, jobs.map { it.get() })
        } finally { executor.shutdownNow() }
    }
}

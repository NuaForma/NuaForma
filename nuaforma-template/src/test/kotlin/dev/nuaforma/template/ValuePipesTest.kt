package dev.nuaforma.template

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ValuePipesTest {
    private fun pipe(name: String = "convert", arguments: List<OptionDefinition> = emptyList(),
                     action: (Any?, List<Any?>, TemplatePipeContext) -> Any?) = object : TemplatePipe {
        override val definition = TemplatePipeDefinition(name, "Synthetic value conversion", arguments)
        override fun apply(value: Any?, arguments: List<Any?>, context: TemplatePipeContext): Any? = action(value, arguments, context)
    }
    private fun template(source: String, vararg pipes: TemplatePipe) = Templates.compile(source, TemplatePipes.of(pipes.toList()))
    private fun failure(code: DiagnosticCode, action: () -> Unit): GenerationException =
        assertThrows(GenerationException::class.java, action).also { assertEquals(code, it.diagnostics.first().code) }

    @Test fun `typed chains preserve nested values and arguments until scalar insertion`() {
        val lookup = pipe("lookup", listOf(OptionDefinition("key", OptionType.STRING, "Object key", required = true))) { value, args, _ -> (value as Map<*, *>)[args[0]] }
        val first = pipe("first") { value, _, _ -> (value as List<*>).first() }
        val result = template("{obj | lookup(key) | first() | lookup(\"amount\") | number(\"0.00\")}", lookup, first)
            .render(mapOf("obj" to mapOf("entries" to listOf(mapOf("amount" to 1.005))), "key" to "entries"))
        assertEquals("1.00", result)
        failure(DiagnosticCodes.INVALID_REQUEST) { template("{obj | lookup(\"entries\")}", lookup).render(mapOf("obj" to mapOf("entries" to listOf(1)))) }
    }

    @Test fun `defaults allowed values nulls and explicit missing fallback are strict`() {
        val convert = pipe(arguments = listOf(OptionDefinition("prefix", OptionType.STRING, "Prefix", defaultValue = "A", allowedValues = listOf("A", "B")))) { value, args, _ -> if (value == null) args[0] else "${args[0]}$value" }
        assertEquals("A", template("{missing ?? null | convert()}", convert).render(emptyMap<String, Any?>()))
        assertEquals("B", template("{value | convert(\"B\")}", convert).render(mapOf("value" to null)))
        failure(DiagnosticCodes.MISSING_VALUE) { template("{missing | convert()}", convert).render(emptyMap<String, Any?>()) }
        failure(DiagnosticCodes.MISSING_VALUE) { template("{value | convert(missing)}", convert).render(mapOf("value" to null)) }
        for (args in listOf("true", "\"C\"", "\"A\", \"B\"")) failure(DiagnosticCodes.INVALID_REQUEST) {
            template("{value | convert($args)}", convert).render(mapOf("value" to "x"))
        }
        val required = pipe(arguments = listOf(OptionDefinition("text", OptionType.STRING, "Required", required = true))) { _, args, _ -> args[0] }
        failure(DiagnosticCodes.INVALID_REQUEST) { template("{null | convert(null)}", required).render(emptyMap<String, Any?>()) }
        assertEquals("", template("{null | convert()}", pipe { _, _, _ -> null }).render(emptyMap<String, Any?>()))
    }

    @Test fun `unselected branches validate names and grammar without executing extensions`() {
        val convert = pipe { _, _, _ -> throw AssertionError("must not execute") }
        assertEquals("yes", template("{#if true}yes{#else}{missing | convert()}{/if}", convert).render(emptyMap<String, Any?>()))
        for (source in listOf("{#if true}yes{#else}{null | absent()}{/if}", "{null | convert}", "{null | convert(1,) }", "{#if true | convert()}yes{/if}")) {
            failure(DiagnosticCodes.INVALID_TEMPLATE) { template(source, convert) }
        }
        assertEquals("a|b", template("{\"a|b\" | convert(contains(\"x\", \"x\") || false)}",
            pipe(arguments = listOf(OptionDefinition("enabled", OptionType.BOOLEAN, "Flag", required = true))) { value, _, _ -> value }).render(emptyMap<String, Any?>()))
    }

    @Test fun `unsupported results failures and cycles are sanitized without stringification`() {
        val bad = object { override fun toString(): String = throw AssertionError("private-value") }
        val cycle = mutableListOf<Any?>().apply { add(this) }
        val results = listOf(bad, mapOf(1 to "x"), cycle, Double.NaN, "\uD800")
        results.forEach { value ->
            val error = failure(DiagnosticCodes.INVALID_REQUEST) { template("{null | convert()}", pipe { _, _, _ -> value }).render(emptyMap<String, Any?>()) }
            assertNull(error.cause)
            assertFalse(error.message.orEmpty().contains("private-value"))
        }
        val error = failure(DiagnosticCodes.INVALID_REQUEST) { template("{null | convert()}", pipe { _, _, _ -> throw IllegalStateException("private-value") }).render(emptyMap<String, Any?>()) }
        assertFalse(error.message.orEmpty().contains("private-value")); assertNull(error.cause)
    }

    @Test fun `inputs arguments and results are detached deeply immutable snapshots`() {
        val intermediate = mutableMapOf<String, Any?>("items" to mutableListOf("original"))
        val source = mutableMapOf<String, Any?>("items" to mutableListOf("input"))
        val first = pipe("first", listOf(OptionDefinition("obj", OptionType.OBJECT, "Object", required = true))) { value, args, _ ->
            for (item in listOf(value, args[0])) assertThrows(UnsupportedOperationException::class.java) { ((item as Map<*, *>)["items"] as MutableList<Any?>).clear() }
            intermediate
        }
        val second = pipe("second") { value, _, _ ->
            intermediate["items"] = listOf("changed")
            ((value as Map<*, *>)["items"] as List<*>).first()
        }
        assertEquals("original", template("{obj | first(obj) | second()}", first, second).render(mapOf("obj" to source)))
        assertEquals(listOf("input"), source["items"])
    }

    @Test fun `bounded results and cumulative scopes share limits and cancellation`() {
        val convert = pipe { _, _, _ -> "12345" }
        val budget = GenerationContext(GenerationLimits(maxExpandedBytes = 8))
        val session = TemplateSession.open(emptyMap<String, Any?>(), TemplatePipes.of(listOf(convert)), context = budget)
        assertEquals("12345", session.expand("{null | convert()}").single().text)
        failure(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) { session.branch().expand("{null | convert()}") }
        failure(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) {
            template("{null | convert()}", pipe { _, _, _ -> List(100001) { null } }).render(emptyMap<String, Any?>())
        }
        failure(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) {
            template("{null | convert()}", pipe { _, _, _ -> BigDecimal("1e1001") }).render(emptyMap<String, Any?>())
        }
        val cancelled = AtomicBoolean()
        val cancelPipe = pipe { _, _, ctx -> cancelled.set(true); ctx.checkActive(); "ignored" }
        failure(DiagnosticCodes.CANCELLED) { template("{null | convert()}", cancelPipe).render(emptyMap<String, Any?>(), GenerationOptions(),
            GenerationContext(cancellation = CancellationToken { cancelled.get() })) }
    }

    @Test fun `explicit formatting policy and literals survive concurrent program reuse`() {
        val convert = pipe { value, _, ctx -> ctx.checkActive(); "${ctx.locale.language}:${ctx.timeZone.id}:$value:{private}" }
        val compiled = template("{amount | number(\"0.00\") | convert()}", convert)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..8).map { n -> executor.submit<String> {
                compiled.render(mapOf("amount" to n), GenerationOptions(locale = Locale.US, timeZone = ZoneId.of("Europe/Amsterdam")), GenerationContext())
            } }
            jobs.forEachIndexed { index, future -> assertEquals("en:Europe/Amsterdam:${index + 1}.00:{private}", future.get()) }
        } finally { executor.shutdownNow() }
        assertEquals("2026-01-01", template("{null | convert() | date(\"uuuu-MM-dd\")}", pipe { _, _, _ -> "2026-01-01" }).render(emptyMap<String, Any?>()))
    }

    @Test fun `registration collisions and mixed content chains never depend on ordering`() {
        failure(DiagnosticCodes.INVALID_REQUEST) { TemplatePipes.of(listOf(pipe { _, _, _ -> null }, pipe { _, _, _ -> null })) }
        assertThrows(IllegalArgumentException::class.java) { TemplatePipeDefinition("number", "Reserved") }
        val content = object : ContentPipe {
            override val definition = ContentPipeDefinition("content", "Text")
            override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext) = ContentResult.Text("text")
        }
        val values = TemplatePipes.of(listOf(pipe { value, _, _ -> value }))
        val session = ContentTemplates.open(emptyMap<String, Any?>(), listOf(content), values)
        for (source in listOf("{null | convert() | content()}", "{null | content() | convert()}")) failure(DiagnosticCodes.INVALID_TEMPLATE) { session.validate(source) }
        failure(DiagnosticCodes.INVALID_REQUEST) { ContentTemplates.open(emptyMap<String, Any?>(), listOf(content), TemplatePipes.of(listOf(pipe("content") { _, _, _ -> null }))) }
    }
}

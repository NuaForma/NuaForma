package dev.nuaforma.template

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class TemplateLanguageTest {
    private fun render(source: String, data: Map<String, *> = emptyMap<String, Any?>()) = Templates.compile(source).render(data)

    @Test
    fun `paths access arbitrary nested objects quoted keys and list indexes`() {
        val data = mapOf("customer" to mapOf("name" to "Ada", "billing-address" to listOf(mapOf("city" to "Utrecht"))),
            "class" to "ordinary data", "loop" to "ordinary key", "秘密" to "value")
        assertEquals("Ada Utrecht ordinary data ordinary key value", render(
            "{customer.name} {data.customer[\"billing-address\"][0].city} {class} {data.loop} {data[\"秘密\"]}", data))
    }

    @Test
    fun `nested conditions stay inline and preserve literal whitespace exactly`() {
        val source = "Dear {customer.name}, you {#if active}have {#if count >= 2}multiple orders{#else}one order{/if}{#else}have no orders{/if}.\nNext line."
        val data = mapOf("customer" to mapOf("name" to "Ada"), "active" to true, "count" to 2)
        assertEquals("Dear Ada, you have multiple orders.\nNext line.", render(source, data))
        assertEquals("Dear Ada, you have one order.\nNext line.", render(source, data + ("count" to 1)))
        assertEquals("Dear Ada, you have no orders.\nNext line.", render(source, data + ("active" to false)))
    }

    @Test
    fun `block conditions remove only selected text not automatically surrounding newlines`() {
        assertEquals("Start\nEnd", render("Start\n{#if include}Optional\n{/if}End", mapOf("include" to false)))
        assertEquals("Start\nOptional\nEnd", render("Start\n{#if include}Optional\n{/if}End", mapOf("include" to true)))
    }

    @Test
    fun `repetition scopes restore aliases and loop metadata including nested shadowing`() {
        val data = mapOf("item" to "root", "groups" to listOf(mapOf("name" to "A", "items" to listOf("x", "y")),
            mapOf("name" to "B", "items" to listOf("z"))))
        val source = "{#each groups as item}{loop.number}:{item.name}[{#each item.items as item}{loop.index}={item}{#if !loop.last},{/if}{/each}] {item.name};{/each}{item}"
        assertEquals("1:A[0=x,1=y] A;2:B[0=z] B;root", render(source, data))
        assertEquals("", render("{#each rows as row}{row}{/each}", mapOf("rows" to emptyList<String>())))
    }

    @Test
    fun `missing null and type policies are explicit`() {
        assertEquals("[] fallback backup", render("[{nil}] {absent ?? \"fallback\"} {nil ?? \"backup\"}", mapOf("nil" to null)))
        assertEquals("true false", render("{nil == null} {name == null}", mapOf("nil" to null, "name" to "Ada")))
        assertEquals("missing", render("{rows[2].name ?? \"missing\"}", mapOf("rows" to listOf(mapOf("name" to "Ada")))))
        assertFailure(DiagnosticCodes.MISSING_VALUE, "{absent}")
        assertFailure(DiagnosticCodes.MISSING_VALUE, "{nil.name}", mapOf("nil" to null))
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "{customer}", mapOf("customer" to mapOf("name" to "Ada")))
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "{#if count}yes{/if}", mapOf("count" to 1))
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "{1 == \"1\"}")
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "{name.city}", mapOf("name" to "Ada"))
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "{#each rows as row}{row}{/each}", mapOf("rows" to null))
    }

    @Test
    fun `operators use numeric equality and short circuit without coercion`() {
        assertEquals("true true false true", render("{1 == 1.00} {1e2 >= 99} {!true} {(false || true) && true}"))
        assertEquals("false true ok", render("{false && missing} {true || missing} {\"ok\" ?? missing}"))
        assertEquals("true", render("{a == b}", mapOf("a" to 0.1, "b" to BigDecimal("0.10"))))
    }

    @Test
    fun `inserted data is literal and escaped template braces are unambiguous`() {
        val value = "{#if true}{secret}{/if} {{ x }}"
        assertEquals("Literal {customer.name}: $value", render("Literal {{customer.name}}: {text}", mapOf("text" to value)))
        assertEquals("{x}", render("{\"{x}\"}"))
    }

    @Test
    fun `numbers and dates use explicit locale timezone and half even rounding`() {
        val compiled = Templates.compile("{amount | number(\"#,##0.00\")} {date | date(\"uuuu-MM-dd HH:mm\")} {day | date(\"dd MMM uuuu\")}")
        val options = GenerationOptions(locale = Locale.forLanguageTag("nl-NL"), timeZone = ZoneId.of("Europe/Amsterdam"))
        assertEquals("1.234,56 2026-01-01 01:30 01 jan. 2026", compiled.render(
            mapOf("amount" to BigDecimal("1234.565"), "date" to "2026-01-01T00:30:00Z", "day" to "2026-01-01"), options, GenerationContext()))
        assertEquals("1234.5 1.00 true", render("{amount} {1.005 | number(\"0.00\")} {true}", mapOf("amount" to BigDecimal("1234.5000"))))
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "{date | date(\"uuuu-MM-dd\")}", mapOf("date" to "2026-02-30"))
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "{date | date(\"HH:mm\")}", mapOf("date" to "2026-01-01"))
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "{date | date(\"uuuu-MM-dd\")}", mapOf("date" to "2026-01-01T10:00:00"))
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "{amount | number(\"0.00\")}", mapOf("amount" to "1.00"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["{", "}", "{}", "{#if true}x", "{#else}", "{/each}", "{#each items as data}x{/each}",
        "{#if true}x{/each}", "{customer.getName()}", "{new File(\"SECRET\")}", "{Class.forName(\"SECRET\")}",
        "{T(java.lang.Runtime).getRuntime()}", "{data.getClass()}", "{System.getenv(\"SECRET\")}",
        "{fetch(\"https://example.invalid\")}", "{eval(\"SECRET\")}", "{1 + 2}", "{a = 1}", "{a[-1]}",
        "{a[other]}", "{1.2.3}", "{01}", "{#if false}{unknown()} {/if}", "{amount | unknown(\"0\")}",
        "{amount | number(\"invalid\")}", "{date | date(\"invalid\")}", "{\"\\uD800\"}"])
    fun `malformed syntax and executable language constructs are rejected at compilation`(source: String) {
        val error = assertThrows(GenerationException::class.java) { Templates.compile(source) }
        assertEquals(DiagnosticCodes.INVALID_TEMPLATE, error.diagnostics.single().code)
        assertFalse(error.diagnostics.single().message.contains("SECRET"))
        assertNull(error.cause)
    }

    @Test
    fun `expression template and expansion budgets are enforced including multibyte text`() {
        fun context(limits: GenerationLimits) = GenerationContext(limits)
        assertThrows(GenerationException::class.java) { Templates.compile("{a.b.c}", context(GenerationLimits(maxExpressionDepth = 2))) }
        assertThrows(GenerationException::class.java) { Templates.compile("{" + "!".repeat(200) + "true}") }
        assertThrows(GenerationException::class.java) { Templates.compile("{" + "(".repeat(200) + "true" + ")".repeat(200) + "}") }
        assertThrows(GenerationException::class.java) { Templates.compile("{#if true}".repeat(200) + "x" + "{/if}".repeat(200)) }
        assertThrows(GenerationException::class.java) { Templates.compile("{" + "1 == ".repeat(1000) + "1}") }
        val byteError = assertThrows(GenerationException::class.java) { Templates.compile("😀", context(GenerationLimits(maxTemplateBytes = 3))) }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, byteError.diagnostics.single().code)
        val compiled = Templates.compile("{text}")
        val outputError = assertThrows(GenerationException::class.java) { compiled.render(mapOf("text" to "😀"), GenerationOptions(), context(GenerationLimits(maxOutputBytes = 3))) }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, outputError.diagnostics.single().code)
        assertThrows(GenerationException::class.java) { compiled.render(mapOf("text" to "abcdef"), GenerationOptions(), context(GenerationLimits(maxExpandedBytes = 5))) }
        val repeated = Templates.compile("{#each rows as a}{#each rows as b}{b}{/each}{/each}")
        val growthError = assertThrows(GenerationException::class.java) { repeated.render(mapOf("rows" to listOf(1, 2)), GenerationOptions(), context(GenerationLimits(maxRepetitions = 3))) }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, growthError.diagnostics.single().code)
        assertThrows(GenerationException::class.java) { render("{number}", mapOf("number" to BigDecimal("1e100000"))) }
        assertThrows(GenerationException::class.java) { render("{number}", mapOf("number" to java.math.BigInteger.TEN.pow(10001))) }
        assertThrows(GenerationException::class.java) { Templates.compile("x".repeat(100_001) + "{true}".repeat(100_001)) }
    }

    @Test
    fun `compiled programs recheck stricter depth and handle cancellation and deadlines`() {
        val compiled = Templates.compile("{a.b.c}")
        assertThrows(GenerationException::class.java) { compiled.render(emptyMap<String, Any?>(), GenerationOptions(), GenerationContext(GenerationLimits(maxExpressionDepth = 2))) }
        val cancelled = GenerationContext(cancellation = CancellationToken { true })
        assertThrows(GenerationException::class.java) { Templates.compile("hello", cancelled) }
        assertThrows(GenerationException::class.java) { Templates.compile("hello").render(emptyMap<String, Any?>(), GenerationOptions(), cancelled) }
        assertThrows(GenerationException::class.java) { Templates.compile("hello", GenerationContext(deadline = Instant.EPOCH)) }
        var checks = 0
        val running = GenerationContext(cancellation = CancellationToken { ++checks > 2000 }, limits = GenerationLimits(maxDuration = Duration.ofMinutes(1)))
        assertThrows(GenerationException::class.java) { Templates.compile("{#each rows as row}{row}{/each}").render(mapOf("rows" to (1..1000).toList()), GenerationOptions(), running) }
    }

    @Test
    fun `work budget bounds many cheap operations and repetitions with empty bodies`() {
        val compiled = Templates.compile("{#each rows as row}" + "{true}".repeat(1000) + "{/each}")
        val error = assertThrows(GenerationException::class.java) { compiled.render(mapOf("rows" to (1..1000).toList())) }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, error.diagnostics.single().code)
        val empty = Templates.compile("{#each rows as row}{/each}")
        val repeatError = assertThrows(GenerationException::class.java) { empty.render(mapOf("rows" to listOf(1, 2)), GenerationOptions(), GenerationContext(GenerationLimits(maxRepetitions = 1))) }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, repeatError.diagnostics.single().code)
    }

    @Test
    fun `all data is validated without introspection and diagnostics contain no values`() {
        class Secret { fun getValue(): String = error("Never invoke application methods") }
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "unused", mapOf("SECRET" to Secret()))
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "unused", mapOf("number" to Double.NaN))
        val cycle = mutableListOf<Any?>(); cycle.add(cycle)
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "unused", mapOf("cycle" to cycle))
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "{number | number(\"0.00\")}", mapOf("number" to "SECRET"))
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "{date | date(\"uuuu\")}", mapOf("date" to "SECRET"))
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "unused", mapOf("text" to "\uD800"))
        @Suppress("UNCHECKED_CAST")
        val wrongKeys = mapOf(1 to "value") as Map<String, *>
        assertFailure(DiagnosticCodes.INVALID_REQUEST, "unused", wrongKeys)
    }

    @Test
    fun `bounded malformed marker fuzzing never leaks unchecked parser failures`() {
        val random = kotlin.random.Random(6)
        val characters = "abc012{}[]()!<>=?&|.#/\\\" -"
        repeat(1500) {
            val source = "{" + (0 until random.nextInt(0, 80)).map { characters[random.nextInt(characters.length)] }.joinToString("") + "}"
            try { Templates.compile(source).render(emptyMap<String, Any?>()) }
            catch (error: GenerationException) {
                assertTrue(error.diagnostics.isNotEmpty())
                assertNull(error.cause)
            }
        }
    }

    @Test
    fun `one immutable program renders concurrently without data mutation or leakage`() {
        val compiled = Templates.compile("{label}:{#each rows as row}{row}{/each}")
        val executor = Executors.newFixedThreadPool(4)
        try {
            val calls = (1..24).map { index -> Callable {
                val data = mutableMapOf("label" to "request-$index", "rows" to mutableListOf(index))
                val result = compiled.render(data)
                assertEquals(mapOf("label" to "request-$index", "rows" to listOf(index)), data)
                result
            } }
            assertEquals((1..24).map { "request-$it:$it" }, executor.invokeAll(calls).map { it.get() })
        } finally { executor.shutdownNow() }
    }

    private fun assertFailure(code: DiagnosticCode, source: String, data: Map<String, *> = emptyMap<String, Any?>()) {
        val error = assertThrows(GenerationException::class.java) { render(source, data) }
        assertEquals(code, error.diagnostics.single().code)
        assertFalse(error.diagnostics.single().message.contains("SECRET"))
        assertNull(error.cause)
    }
}

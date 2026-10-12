package dev.nuaforma.template

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Duration

class StringFunctionsTest {
    private fun render(source: String, data: Map<String, *> = emptyMap<String, Any?>()) = Templates.compile(source).render(data)

    @Test
    fun `documented examples and null checks distinguish booleans from truthiness`() {
        val data = mapOf("description" to "Met spoed", "reference" to "NF-001", "filename" to "letter.pdf",
            "customer" to mapOf("name" to " Ada "), "status" to " APPROVED ", "name" to null)
        assertEquals("Prioriteit", render("{#if contains(description, \"spoed\")}Prioriteit{/if}", data))
        assertEquals("NuaForma-referentie", render("{#if startsWith(reference, \"NF-\")}NuaForma-referentie{/if}", data))
        assertEquals("PDF-document", render("{#if endsWith(filename, \".pdf\")}PDF-document{/if}", data))
        assertEquals("Ada", render("{trim(customer.name)}", data))
        assertEquals("Goedgekeurd", render("{#if equalsIgnoreCase(trim(status ?? \"\"), \"approved\")}Goedgekeurd{/if}", data))
        assertEquals("null", render("{#if name == null}null{/if}", data))
        for (values in listOf(emptyMap(), mapOf("name" to null), mapOf("name" to ""), mapOf("name" to " \t\n"))) {
            assertEquals("empty", render("{#if trim(name ?? \"\") == \"\"}empty{/if}", values))
        }
        assertEquals("missing", render("{#if (name ?? null) == null}missing{/if}"))
        assertEquals("Onbekend", render("{name ?? \"Onbekend\"}"))
        assertEquals("", render("{name ?? \"Onbekend\"}", mapOf("name" to "")))
        for (value in listOf(null, "", "yes", 0, emptyList<String>())) {
            failure(DiagnosticCodes.INVALID_REQUEST) { render("{#if name}yes{/if}", mapOf("name" to value)) }
        }
        failure(DiagnosticCodes.INVALID_REQUEST) { render("{#if trim(\"yes\")}yes{/if}") }
        failure(DiagnosticCodes.MISSING_VALUE) { render("{#if name == null}null{/if}") }
    }

    @Test
    fun `case options empty searches and longer searches have explicit semantics`() {
        for (function in listOf("contains", "startsWith", "endsWith")) {
            assertEquals("false true false", render("{$function(\"AbC\", \"abc\")} {$function(\"AbC\", \"abc\", true)} {$function(\"AbC\", \"abc\", false)}"))
            assertEquals("true true false", render("{$function(\"\", \"\")} {$function(\"abc\", \"\")} {$function(\"a\", \"aa\")}"))
        }
        assertEquals("true false", render("{equalsIgnoreCase(\"\", \"\")} {equalsIgnoreCase(\"a\", \"aa\")}"))
        assertEquals("true", render("{contains(\"a.b*\", \".b*\")}"))
        assertEquals("true false", render("{!contains(\"abc\", \"x\") && endsWith(\"abc\", \"c\")} {false && contains(missing, \"x\")}"))
        assertEquals("true", render("{contains(trim(value), search, insensitive)}", mapOf("value" to " AbC ", "search" to "b", "insensitive" to true)))
        assertEquals("data", render("{trim}", mapOf("trim" to "data")))
    }

    @Test
    fun `Unicode simple case matching is locale independent without accent or expansion folding`() {
        assertEquals("true true true true false false", render(
            "{equalsIgnoreCase(\"É\", \"é\")} {equalsIgnoreCase(\"Σ\", \"ς\")} {equalsIgnoreCase(\"I\", \"ı\")} " +
                "{equalsIgnoreCase(\"𐐀\", \"𐐨\")} {equalsIgnoreCase(\"é\", \"e\")} {equalsIgnoreCase(\"ß\", \"ss\")}"))
        assertEquals("true true true", render("{contains(\"x𐐀z\", \"𐐨\", true)} {startsWith(\"𐐀z\", \"𐐨\", true)} {endsWith(\"x𐐀\", \"𐐨\", true)}"))
        assertEquals("A  B", render("{trim(value)}", mapOf("value" to "\u00a0\t A  B \u2003\n")))
        assertEquals("\u200bA\u200b", render("{trim(value)}", mapOf("value" to "\u200bA\u200b")))
    }

    @Test
    fun `overlapping searches agree with literal reference operations`() {
        val random = Random(42)
        repeat(150) {
            fun text(length: Int) = (0 until length).map { "abAB"[random.nextInt(4)] }.joinToString("")
            val source = text(random.nextInt(40))
            val search = text(random.nextInt(12))
            val data = mapOf("source" to source, "search" to search)
            for (ignoreCase in listOf(false, true)) {
                val actual = render("{contains(source, search, $ignoreCase)} {startsWith(source, search, $ignoreCase)} {endsWith(source, search, $ignoreCase)}", data)
                assertEquals("${source.contains(search, ignoreCase)} ${source.startsWith(search, ignoreCase)} ${source.endsWith(search, ignoreCase)}", actual)
            }
        }
        assertEquals("false", render("{contains(source, search)}", mapOf("source" to "a".repeat(30_000), "search" to "a".repeat(15_000) + "b")))
    }

    @Test
    fun `unknown calls methods and malformed argument lists fail even in unselected branches`() {
        for (expression in listOf("unknown(\"x\")", "value.trim()", "data[\"trim\"](\"x\")", "trim()", "trim(\"x\", \"y\")",
            "contains(\"x\")", "contains(\"x\", \"y\", true, false)", "equalsIgnoreCase(\"x\", \"y\", true)",
            "contains(,\"x\")", "contains(\"x\",)", "trim(\"x\")()", "TRIM(\"x\")", "matches(\"x\", \".*\")")) {
            failure(DiagnosticCodes.INVALID_TEMPLATE) { Templates.compile("{#if false}{$expression}{/if}") }
        }
    }

    @Test
    fun `strings and case flags are strict and confidential values stay out of diagnostics`() {
        for (value in listOf(null, true, 42, listOf("PRIVATE"), mapOf("PRIVATE" to "PRIVATE"))) {
            failure(DiagnosticCodes.INVALID_REQUEST) { render("{trim(value)}", mapOf("value" to value)) }
            failure(DiagnosticCodes.INVALID_REQUEST) { render("{contains(\"PRIVATE\", value)}", mapOf("value" to value)) }
        }
        for (value in listOf(null, "true", 1)) {
            failure(DiagnosticCodes.INVALID_REQUEST) { render("{contains(\"PRIVATE\", \"P\", flag)}", mapOf("flag" to value)) }
        }
        failure(DiagnosticCodes.MISSING_VALUE) { render("{trim(missing)}") }
        failure(DiagnosticCodes.MISSING_VALUE) { render("{startsWith(\"PRIVATE\", missing)}") }
        failure(DiagnosticCodes.MISSING_VALUE) { render("{endsWith(\"PRIVATE\", \"E\", missing)}") }
        failure(DiagnosticCodes.INVALID_REQUEST) { render("{trim(value.city ?? \"\")}", mapOf("value" to "PRIVATE")) }
    }

    @Test
    fun `function nesting and repeated scans share processing budgets`() {
        failure(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) {
            Templates.compile("{trim(trim(trim(\"x\")))}", GenerationContext(GenerationLimits(maxExpressionDepth = 3)))
        }
        val session = TemplateSession.open(mapOf("source" to "x".repeat(10_000)))
        failure(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) { repeat(110) { session.test("contains(source, \"\")") } }
        val siblings = TemplateSession.open(mapOf("source" to "x".repeat(10_000), "rows" to listOf(1, 2))).repeat("rows", "row")
        repeat(60) { assertTrue(siblings[0].test("startsWith(source, \"\")")) }
        failure(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) { repeat(60) { siblings[1].test("endsWith(source, \"\")") } }
    }

    @Test
    fun `long string processing checks cancellation and never reinterprets values`() {
        val compiled = Templates.compile("{trim(value)}")
        var checks = 0
        failure(DiagnosticCodes.CANCELLED) {
            compiled.render(mapOf("value" to " ".repeat(20_000)), GenerationOptions(),
                GenerationContext(cancellation = CancellationToken { ++checks > 1000 }))
        }
        assertEquals("{secret}", render("{trim(value)}", mapOf("value" to " {secret} ")))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((0..11).map { index -> Callable { compiled.render(mapOf("value" to " Tenant$index ")) } })
            results.forEachIndexed { index, result -> assertEquals("Tenant$index", result.get()) }
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `oversized search storage is rejected before allocation and scans observe deadlines`() {
        failure(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) {
            render("{contains(source, \"x\")}", mapOf("source" to "a".repeat(1_000_001)))
        }
        var time = Instant.EPOCH
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = time
        }
        var checks = 0
        val context = GenerationContext(GenerationLimits(maxDuration = Duration.ofSeconds(1)),
            CancellationToken { if (++checks > 1000) time = Instant.EPOCH.plusSeconds(2); false }, clock = clock)
        failure(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) {
            Templates.compile("{contains(value, \"z\")}").render(mapOf("value" to "a".repeat(20_000)), GenerationOptions(), context)
        }
    }

    private fun failure(code: DiagnosticCode, action: () -> Unit) {
        val error = assertThrows(GenerationException::class.java, action)
        assertEquals(code, error.diagnostics.single().code)
        assertFalse(error.diagnostics.single().message.contains("PRIVATE"))
        assertNull(error.cause)
    }
}

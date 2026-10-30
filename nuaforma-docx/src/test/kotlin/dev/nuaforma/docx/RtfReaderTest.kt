package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.adapters.rtf.BoundedRtfReader
import dev.nuaforma.docx.internal.richtext.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class RtfReaderTest {
    private fun read(source: String, budget: RichTextBudget = RichTextBudget(GenerationContext())): List<RichParagraph> {
        budget.source(source)
        return BoundedRtfReader(source, budget).read()
    }
    private fun plain(source: String) = read(source).map { it.spans.joinToString("") { span -> span.text } }
    private fun reject(source: String, code: DiagnosticCode = DiagnosticCodes.INVALID_REQUEST) =
        assertThrows(GenerationException::class.java) { read(source) }.also { assertEquals(code, it.diagnostics.first().code, source.take(60)) }

    @Test fun `nested character overrides restore scope and reset to the template base`() {
        val spans = read("{\\rtf1 A{\\b B{\\i C}D}E\\plain F}").single().spans
        assertEquals("ABCDEF", spans.joinToString("") { it.text })
        assertEquals(listOf(null, true, true, true, null), spans.map { it.style.bold })
        assertEquals(listOf(null, null, true, null, null), spans.map { it.style.italic })
        assertEquals("EF", spans.last().text)
        val explicit = read("{\\rtf1\\b0\\i0\\ul0\\strike0 x}").single().spans.single().style
        assertEquals(RichStyle(bold = false, italic = false, underline = false, strike = false), explicit)
    }
    @Test fun `paragraph terminators do not invent an extra paragraph and internal blanks survive`() {
        assertEquals(listOf("one", "", "two"), plain("{\\rtf1 one\\par\\par two\\par}"))
        assertEquals(listOf(""), plain("{\\rtf1\\par}"))
        assertEquals(emptyList<String>(), plain("{\\rtf1}"))
        assertEquals(listOf("a\tb\u00a0\u2011{}\\\u2014"), plain("{\\rtf1 a\\tab b\\~\\_\\{\\}\\\\\\emdash}"))
    }
    @Test fun `unicode is signed UTF16 with scoped fallbacks and supplementary pairs`() {
        assertEquals(listOf("Ж 😀 éê"), plain("{\\rtf1\\uc1\\u1046? \\u-10179?\\u-8704? {\\uc0\\u233}\\u234?}"))
        assertEquals(listOf("éX"), plain("{\\rtf1\\uc2\\u233\\~?X}"))
        assertEquals(listOf("éX"), plain("{\\rtf1\\uc1\\u233\\par X}"))
        assertEquals(listOf("éX"), plain("{\\rtf1\\uc1\\u233{X}}"))
    }
    @Test fun `hex bytes use explicit code pages and strictly decode multibyte utf8`() {
        assertEquals(listOf("Жé"), plain("{\\rtf1\\ansicpg1251\\'c6\\u233?}"))
        assertEquals(listOf("é😀"), plain("{\\rtf1\\ansicpg65001\\'c3\\'a9\\'f0\\'9f\\'98\\'80}"))
        reject("{\\rtf1\\ansicpg65001\\'c3}")
        reject("{\\rtf1\\ansicpg1252\\'81}")
        reject("{\\rtf1\\ansicpg999 a}")
    }
    @Test fun `font color size underline and strike remain explicit immutable overrides`() {
        val source = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fnil\\fcharset0 Arial;}{\\f1\\fswiss\\fcharset204 Other;}}" +
            "{\\colortbl;\\red180\\green10\\blue20;}{\\*\\generator Synthetic 1.0;}\\viewkind4\\uc1\\pard\\f0\\fs24\\cf1\\ul\\strike A{\\f1\\'c6}\\plain B}"
        val spans = read(source).single().spans
        assertEquals("AЖB", spans.joinToString("") { it.text })
        assertEquals(RichStyle(underline = true, strike = true, family = "Arial", halfPoints = 24, color = RichColor(180, 10, 20)), spans.first().style)
        assertEquals("Other", spans[1].style.family)
        assertEquals(RichStyle(), spans.last().style)
        val named = read("{\\rtf1{\\fonttbl{\\f0\\fcharset204 \\'d8\\'f0\\'e8\\'f4\\'f2;}}\\f0\\'c6}").single().spans.single()
        assertEquals("Шрифт", named.style.family)
        assertEquals("Ж", named.text)
        val colors = read("{\\rtf1{\\colortbl\\red255; ;}\\cf0 R\\cf1 A}").single().spans
        assertEquals(RichColor(255, 0, 0), colors.first().style.color)
        assertNull(colors.last().style.color)
    }
    @Test fun `unknown optional active binary external hidden and document-layout constructs fail before consumption`() {
        for (control in listOf("object", "pict", "bin", "field", "fldinst", "v", "stylesheet", "filetbl", "fontemb", "htmltag", "line", "qc", "unknown")) {
            val error = reject("{\\rtf1{\\$control ATTACK_CANARY}}")
            assertEquals(RejectionReason.UNSUPPORTED_RICH_TEXT, error.diagnostics.single().rejectionReason)
            assertFalse(error.diagnostics.single().message.contains("ATTACK_CANARY"))
            assertTrue(error.diagnostics.single().location!!.matches(Regex("rtf:[0-9]+")))
        }
        reject("{\\rtf1{\\*\\unknown ATTACK_CANARY}}")
        reject("{\\rtf1\\u65\\bin0}") // Even Unicode fallback cannot hide a prohibited control.
        reject("{\\rtf1 x{\\fonttbl{\\f0 Arial;}}}")
    }
    @Test fun `malformed groups escapes headers and XML-invalid unicode fail with no partial result`() {
        for (source in listOf("plain", "{\\rtf0 x}", "{\\rtf1 x", "{\\rtf1 x}}", "{\\rtf1}x", "{\\rtf1\\'xz}", "{\\rtf1\\b- x}",
            "{\\rtf1\\u0?}", "{\\rtf1\\u-10179?}", "{\\rtf1\\u-8704?}", "{\\rtf1\\u32768?}", "{\\rtf1\\u10?}")) reject(source)
        reject("{\\rtf1{\\fonttbl{\\f0 Arial}}}")
        reject("{\\rtf1{\\colortbl;\\red999;}}")
        val unpaired = "{\\rtf1 prefix \\u-10179?}"
        assertEquals("rtf:${unpaired.indexOf("\\u")}", reject(unpaired).diagnostics.single().location)
    }
    @Test fun `encoded depth numeric token paragraph and output limits fail predictably`() {
        reject("{\\rtf1 " + "x".repeat(1_048_576) + "}", DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED)
        reject("{\\rtf1 " + "{".repeat(64) + "x" + "}".repeat(64) + "}", DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED)
        reject("{\\rtf1\\fs9999999999 x}", DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED)
        reject("{\\rtf1\\" + "a".repeat(33) + "}", DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED)
        reject("{\\rtf1 " + "\\par".repeat(513) + "}", DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED)
        reject("{\\rtf1 " + "x".repeat(100_001) + "}", DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED)
        reject("{\\rtf1 " + "\\b\\b0".repeat(125_001) + "}", DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED)
        reject("{\\rtf1 " + "\\b x\\b0 y".repeat(2049) + "}", DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED)
    }
    @Test fun `budgets accumulate across fragments and retained model overhead is bounded`() {
        val budget = RichTextBudget(GenerationContext())
        repeat(4) { read("{\\rtf1 " + "\\par".repeat(500) + "}", budget) }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, assertThrows(GenerationException::class.java) {
            read("{\\rtf1 " + "\\par".repeat(100) + "}", budget)
        }.diagnostics.single().code)
        val encoded = RichTextBudget(GenerationContext())
        repeat(4) { encoded.source("x".repeat(1_048_576)) }
        assertThrows(GenerationException::class.java) { encoded.source("x") }
        assertThrows(GenerationException::class.java) { read("{\\rtf1 " + "\\par".repeat(50) + "}",
            RichTextBudget(GenerationContext(GenerationLimits(maxExpandedBytes = 1000)))) }
        val ratio = RichTextBudget(GenerationContext())
        ratio.source("{\\rtf1 x}")
        assertThrows(GenerationException::class.java) { ratio.retain(2_000_000) }
    }
    @Test fun `cancellation and expired deadlines stop parsing without a retained global state`() {
        val calls = AtomicInteger()
        val context = GenerationContext(cancellation = CancellationToken { calls.incrementAndGet() > 4 })
        val error = assertThrows(GenerationException::class.java) { read("{\\rtf1 " + "x".repeat(40_000) + "}", RichTextBudget(context)) }
        assertEquals(DiagnosticCodes.CANCELLED, error.diagnostics.single().code)
        val now = Instant.parse("2026-01-01T00:00:00Z")
        assertThrows(GenerationException::class.java) { read("{\\rtf1 x}", RichTextBudget(GenerationContext(
            deadline = now, clock = Clock.fixed(now, ZoneOffset.UTC)))) }
        assertEquals(listOf("clean"), plain("{\\rtf1 clean}"))
    }
    @Test fun `deadline and cancellation are checked during the parser loop after source validation`() {
        val source = "{\\rtf1 " + "x".repeat(10_000) + "}"
        val calls = AtomicInteger()
        val cutoff = AtomicInteger(Int.MAX_VALUE)
        val budget = RichTextBudget(GenerationContext(cancellation = CancellationToken { calls.incrementAndGet() > cutoff.get() }))
        budget.source(source); cutoff.set(calls.get() + 3)
        assertEquals(DiagnosticCodes.CANCELLED, assertThrows(GenerationException::class.java) { BoundedRtfReader(source, budget).read() }.diagnostics.single().code)
        val now = Instant.parse("2026-01-01T00:00:00Z")
        calls.set(0); cutoff.set(Int.MAX_VALUE)
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = if (calls.incrementAndGet() > cutoff.get()) now.plusSeconds(180) else now
        }
        val timed = RichTextBudget(GenerationContext(clock = clock))
        timed.source(source); cutoff.set(calls.get() + 3)
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, assertThrows(GenerationException::class.java) { BoundedRtfReader(source, timed).read() }.diagnostics.single().code)
        assertEquals(listOf("after"), plain("{\\rtf1 after}"))
    }
    @Test fun `the neutral private fragment collections cannot be mutated through source aliases`() {
        val source = mutableListOf(RichSpan("text", RichStyle()))
        val paragraph = RichParagraph(source); source.clear()
        assertEquals("text", paragraph.spans.single().text)
        assertThrows(UnsupportedOperationException::class.java) { (paragraph.spans as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (read("{\\rtf1 text}") as MutableList<*>).clear() }
    }
}

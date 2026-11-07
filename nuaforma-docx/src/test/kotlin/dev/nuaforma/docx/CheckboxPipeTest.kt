package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.CheckboxPipe
import dev.nuaforma.template.ContentTemplates
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.Locale

class CheckboxPipeTest {

    @Test
    fun `bare marks use the full reserved width while boxed marks keep their inset`() {
        fun width(box: Boolean, mark: String): Int {
            val vector = apply(true, box, mark, "empty") as ContentResult.Vector
            val points = vector.paths.last().points
            return points.maxOf { it.x } - points.minOf { it.x }
        }

        assertEquals(900, width(false, "check"))
        assertEquals(900, width(false, "cross"))
        assertEquals(580, width(true, "check"))
        assertEquals(500, width(true, "cross"))
    }


    @Test
    fun `invalid inherited sizes and colors fail explicitly without integer truncation`() {
        for (properties in listOf("""<w:sz w:val="4294967318"/>""", """<w:color w:val="GGHHII"/>""",
            """<w:color w:val="123456" w:themeColor="accent1"/>""")) {
            val error = assertThrows(AssertionError::class.java) {
                generate("""<w:p><w:r><w:rPr>$properties</w:rPr><w:t>{true | checkbox()}</w:t></w:r></w:p>""")
            }.cause as GenerationException
            assertEquals(RejectionReason.UNSUPPORTED_INLINE_VECTOR_STYLE, error.diagnostics.single().rejectionReason)
            assertEquals("word/document.xml", error.diagnostics.single().part)
        }
    }


    @Test
    fun `expression input and quote normalization are shared and missing values need explicit fallback`() {
        val session = ContentTemplates.open(mapOf("gender" to "Vrouw", "none" to null, "yes" to true), listOf(CheckboxPipe()))
        for (source in listOf("{yes | checkbox()}", "{(gender == 'Vrouw') | checkbox()}",
            "{equalsIgnoreCase(gender, “vrouw”) | checkbox()}", "{none ?? false | checkbox()}")) {
            assertTrue(session.expandContent(source).single().content is ContentResult.Vector)
        }
        for (source in listOf("{none | checkbox()}", "{'false' | checkbox()}", "{1 | checkbox()}",
            "{yes | checkbox(true, 'tick')}", "{yes | checkbox(true, 'check', 'empty', false)}",
            "{#if false}{yes | checkbox('false')}{/if}", "{#if false}{yes | checkbox(true, 'check', 'tick')}{/if}")) {
            assertThrows(GenerationException::class.java) { session.expandContent(source) }
        }
        val missing = assertThrows(GenerationException::class.java) { session.expandContent("{missing | checkbox()}") }
        assertEquals(DiagnosticCodes.MISSING_VALUE, missing.diagnostics.single().code)
        assertSame(ContentResult.Empty, session.expandContent("{missing ?? false | checkbox(false)}").single().content)
    }


    private fun generate(body: String, values: Map<String, *> = emptyMap<String, Any?>(), bands: Boolean = false): Map<String, ByteArray> {
        val parts = SyntheticDocx.parts()
        parts["word/document.xml"] = """<w:document xmlns:w="${SyntheticDocx.W}"><w:body>$body</w:body></w:document>""".toByteArray()
        if (bands) for ((name, root) in listOf("header1.xml" to "hdr", "footer1.xml" to "ftr")) {
            parts["word/$name"] = """<w:$root xmlns:w="${SyntheticDocx.W}"><w:p><w:r><w:t>{true | checkbox()}</w:t></w:r></w:p></w:$root>""".toByteArray()
        }
        val output = ByteArrayOutputStream()
        try {
            DocxProvider().generate(GenerationRequest(SyntheticDocx.zip(parts).inputStream(), GenerationRequirements(
                FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX)),
                mapOf("customer" to mapOf("name" to "Ada"), "reference" to "NF-149") + values), output, GenerationContext())
        } catch (error: GenerationException) {
            throw AssertionError(error.diagnostics.joinToString("\n") { "${it.code} ${it.rejectionReason} ${it.location}" }, error)
        }
        return SyntheticDocx.unzip(output.toByteArray())
    }

    @Test
    fun `split markers repeats conditions table cells and page bands share one insertion implementation`() {
        val run = """<w:r><w:t>{true | check</w:t></w:r><w:r><w:t>box()}</w:t></w:r>"""
        val body = """<w:p>$run</w:p><w:p><w:r><w:t>{#each items as item}{#if item}{item | checkbox()}{#else}{item | checkbox(false)}{/if}{/each}</w:t></w:r></w:p>
            <w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w="2000"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w="2000" w:type="dxa"/></w:tcPr><w:p>$run</w:p></w:tc></w:tr></w:tbl>"""
        val result = generate(body, mapOf("items" to listOf(true, false, true)), bands = true)
        assertEquals(4, Regex("<a:custGeom>").findAll(result.getValue("word/document.xml").decodeToString()).count())
        for (name in listOf("word/header1.xml", "word/footer1.xml")) {
            assertEquals(1, Regex("<a:custGeom>").findAll(result.getValue(name).decodeToString()).count())
        }
    }


    private fun apply(value: Any?, box: Boolean, yes: String, no: String): ContentResult =
        CheckboxPipe().apply(value, listOf(box, yes, no), ContentPipeContext(Locale.ROOT, ZoneId.of("UTC"), GenerationContext()))

    @Test
    fun `every boolean outline and mark combination has deterministic font independent geometry`() {
        for (value in listOf(true, false)) for (box in listOf(true, false))
            for (yes in listOf("check", "cross", "empty")) for (no in listOf("check", "cross", "empty")) {
                val selected = if (value) yes else no
                val result = apply(value, box, yes, no)
                if (!box && selected == "empty") assertSame(ContentResult.Empty, result)
                else {
                    val paths = (result as ContentResult.Vector).paths
                    assertEquals((if (box) 1 else 0) + when (selected) { "check" -> 1; "cross" -> 2; else -> 0 }, paths.size)
                    assertEquals(if (box) 1 else 0, paths.count { it.closed })
                }
            }
    }

    @Test
    fun `strings numbers null and invalid options are never coerced to booleans`() {
        for (value in listOf(null, "false", "true", "Vrouw", 0, 1, emptyList<String>())) {
            assertThrows(GenerationException::class.java) { apply(value, true, "check", "empty") }
        }
        assertThrows(GenerationException::class.java) { apply(true, true, "tick", "empty") }
        val cancelled = GenerationContext(cancellation = CancellationToken { true })
        val error = assertThrows(GenerationException::class.java) {
            CheckboxPipe().apply(true, listOf(true, "check", "empty"), ContentPipeContext(Locale.ROOT, ZoneId.of("UTC"), cancelled))
        }
        assertEquals(DiagnosticCodes.CANCELLED, error.diagnostics.single().code)
    }

    @Test
    fun `generation inserts vector drawing from evaluated comparison without media or symbol fonts`() {
        val parts = SyntheticDocx.parts()
        val body = """<w:p><w:r><w:rPr><w:sz w:val="30"/><w:color w:val="127654"/></w:rPr>
            <w:t>Before {(gender == "Vrouw") | checkbox()} After</w:t></w:r></w:p>"""
        parts["word/document.xml"] = """<w:document xmlns:w="${SyntheticDocx.W}"><w:body>$body</w:body></w:document>""".toByteArray()
        val out = ByteArrayOutputStream()
        DocxProvider().generate(GenerationRequest(SyntheticDocx.zip(parts).inputStream(), GenerationRequirements(
            FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX)),
            mapOf("gender" to "Vrouw", "customer" to mapOf("name" to "Ada"), "reference" to "NF-149")), out, GenerationContext())
        val generated = SyntheticDocx.unzip(out.toByteArray())
        val xml = generated.getValue("word/document.xml").decodeToString()
        assertTrue(xml.contains("custGeom"))
        assertTrue(xml.contains("127654"))
        assertTrue(xml.contains("152400")) // 15pt * .8 * 12700 EMU.
        assertTrue(xml.contains("Before ") && xml.contains(" After"))
        assertFalse(xml.contains("checkbox("))
        assertEquals(parts.keys.filter { it.startsWith("word/media/") }.toSet(), generated.keys.filter { it.startsWith("word/media/") }.toSet())
    }
}

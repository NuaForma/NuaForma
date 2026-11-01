package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class TemplateDiagnosticCopyTest {
    private val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX))
    private fun development() = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
    private fun paragraph(text: String) = "<w:p><w:r><w:t>$text</w:t></w:r></w:p>"
    private fun source(body: String, part: String = "word/document.xml"): ByteArray {
        val parts = SyntheticDocx.parts()
        if (part == "word/document.xml") {
            parts[part] = parts.getValue(part).decodeToString().replace(Regex("<w:body>[\\s\\S]*</w:body>"), "<w:body>$body</w:body>").toByteArray()
        } else parts[part] = "<w:hdr xmlns:w=\"${SyntheticDocx.W}\">$body</w:hdr>".toByteArray()
        return SyntheticDocx.zip(parts)
    }
    private fun copy(source: ByteArray, context: GenerationContext = development()): Pair<Diagnostic, Map<String, ByteArray>> {
        val snapshot = source.copyOf()
        val output = ByteArrayOutputStream()
        val diagnostic = DocxProvider().diagnosticCopy(GenerationRequest(source.inputStream(), requirements), output, context, "Arial")
        assertArrayEquals(snapshot, source)
        return diagnostic to SyntheticDocx.unzip(output.toByteArray())
    }

    @Test fun `marks actual failed text span and preserves prefix suffix original markers and unrelated parts`() {
        val input = source(paragraph("First {valid}") + "<w:p><w:r><w:t>Prefix {br</w:t></w:r><w:r><w:t>oken</w:t></w:r></w:p>")
        val (diagnostic, parts) = copy(input)
        assertEquals("paragraph:2", diagnostic.documentLocation)
        assertEquals("word/document.xml", diagnostic.part)
        assertEquals("{broken", diagnostic.templateContext!!.expression)
        val xml = parts.getValue("word/document.xml").decodeToString()
        assertTrue(xml.contains("[ERROR 1] {br"), xml)
        assertTrue(xml.contains("First {valid}"))
        assertTrue(xml.contains("C00000"))
        assertTrue(xml.contains("TEMPLATE DIAGNOSTIC - ERROR 1"))
        assertTrue(xml.contains("UNCLOSED_TEMPLATE_MARKER"))
        for ((name, bytes) in SyntheticDocx.unzip(input)) if (name != "word/document.xml") {
            if (name.endsWith(".xml") || name.endsWith(".rels")) assertEquals(SyntheticDocx.semantic(bytes), SyntheticDocx.semantic(parts.getValue(name)), name)
            else assertArrayEquals(bytes, parts.getValue(name), name)
        }
    }

    @Test fun `tracks hyperlink table header and mixed run breaks without flattening Word objects`() {
        val bad = "<w:r><w:t>Prefix {broken</w:t></w:r>"
        val cases = listOf(
            "<w:p><w:hyperlink>$bad</w:hyperlink></w:p>",
            "<w:tbl><w:tr><w:tc><w:p>$bad</w:p></w:tc></w:tr></w:tbl>",
            "<w:p><w:r><w:t>Prefix {broken</w:t><w:br/><w:t>}</w:t></w:r></w:p>",
            "<w:p>$bad<w:sdt><w:sdtPr/><w:sdtContent><w:r><w:t>X</w:t></w:r></w:sdtContent></w:sdt><w:r><w:t>}</w:t></w:r></w:p>"
        )
        for (body in cases) {
            val (diagnostic, result) = copy(source(body))
            assertEquals("paragraph:1", diagnostic.documentLocation)
            assertTrue(result.getValue("word/document.xml").decodeToString().contains("[ERROR 1]"))
        }
        val (header, result) = copy(source(paragraph("{broken"), "word/header1.xml"))
        assertEquals("word/header1.xml", header.part)
        assertTrue(result.getValue("word/header1.xml").decodeToString().contains("[ERROR 1]"))
        assertTrue(result.getValue("word/document.xml").decodeToString().contains("Part: word/header1.xml"))
    }

    @Test fun `structural block failures mark their own opening paragraph`() {
        val opening = "<w:p><w:r><w:rPr><w:rFonts w:ascii=\"Marker Family\" w:hAnsi=\"Marker Family\"/></w:rPr><w:t>{#if true}</w:t></w:r></w:p>"
        val (diagnostic, result) = copy(source(opening + paragraph("Body")))
        assertEquals(RejectionReason.UNCLOSED_DOCX_CONDITION, diagnostic.rejectionReason)
        assertEquals("paragraph:1", diagnostic.documentLocation)
        assertTrue(result.getValue("word/document.xml").decodeToString().contains("[ERROR 1]"))
        val firstRun = Regex("<w:r>[\\s\\S]*?</w:r>").find(result.getValue("word/document.xml").decodeToString())!!.value
        assertTrue(firstRun.contains("[ERROR 1]"))
        assertTrue(firstRun.contains("Marker Family"), firstRun)
    }

    @Test fun `production and valid templates never publish diagnostic output`() {
        for ((input, context, reason) in listOf(
            Triple(source(paragraph("{broken")), GenerationContext(), RejectionReason.TEMPLATE_DIAGNOSTICS_REQUIRE_DEVELOPMENT),
            Triple(source(paragraph("{valid}")), development(), RejectionReason.TEMPLATE_DIAGNOSTIC_REQUIRES_PARSER_ERROR)
        )) {
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                DocxProvider().diagnosticCopy(GenerationRequest(input.inputStream(), requirements), output, context, "Arial")
            }
            assertEquals(reason, error.diagnostics.first().rejectionReason)
            assertEquals(0, output.size())
        }
    }

    @Test fun `request private source spans and copies remain isolated under concurrent failures`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..8).map { id -> Callable {
                val (_, result) = copy(source(paragraph("{broken$id")))
                val xml = result.getValue("word/document.xml").decodeToString()
                assertTrue(xml.contains("{broken$id"))
                for (other in 1..8) if (other != id) assertFalse(xml.contains("{broken$other"))
            } }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally { executor.shutdownNow() }
    }

    @Test fun `byte limits cancellation and secret excerpt policy remain enforced`() {
        for ((limits, token, expected) in listOf(
            Triple(GenerationLimits(maxTemplateBytes = 1), CancellationToken.NONE, DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED),
            Triple(GenerationLimits(maxOutputBytes = 1), CancellationToken.NONE, DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED),
            Triple(GenerationLimits(), CancellationToken { true }, DiagnosticCodes.CANCELLED)
        )) {
            val context = GenerationContext(limits, token, null, java.time.Clock.systemUTC(), GenerationLogging(LoggingMode.DEVELOPMENT))
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                DocxProvider().diagnosticCopy(GenerationRequest(source(paragraph("{broken")).inputStream(), requirements), output, context, "Arial")
            }
            assertEquals(expected, error.diagnostics.first().code)
            assertEquals(0, output.size())
        }
        val (diagnostic, _) = copy(source(paragraph("{password")))
        assertTrue(diagnostic.templateContext!!.redacted)
        assertEquals("[redacted expression]", diagnostic.templateContext!!.expression)
    }
    private fun visibleText(xml: String): String {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val nodes = factory.newDocumentBuilder().parse(xml.byteInputStream()).getElementsByTagNameNS(SyntheticDocx.W, "t")
        return (0 until nodes.length).joinToString("") { nodes.item(it).textContent }
    }
    private fun preview(body: String): String {
        val output = ByteArrayOutputStream()
        DocxProvider().inspectionCopy(GenerationRequest(source(body).inputStream(), requirements, mapOf("name" to "Ada", "customer" to mapOf("name" to "Example"), "reference" to "REF")),
            output, development(), null, true)
        return SyntheticDocx.unzip(output.toByteArray()).getValue("word/document.xml").decodeToString()
    }

    @Test fun `preview preserves malformed spans and evaluates only independent valid text`() {
        val xml = preview(paragraph("Hello {name}") + paragraph("Broken {name") + paragraph("After {name}"))
        assertTrue(visibleText(xml).contains("Hello Ada"), xml)
        assertTrue(xml.contains("Broken {name"), xml)
        assertTrue(xml.contains("[ERROR]"), xml)
        assertTrue(visibleText(xml).contains("After Ada"), xml)
        assertTrue(xml.contains("NOT A GENERATED DOCUMENT"), xml)
        val strict = ByteArrayOutputStream()
        assertThrows(GenerationException::class.java) {
            DocxProvider().generate(GenerationRequest(source(paragraph("Broken {name")).inputStream(), requirements), strict, development())
        }
        assertEquals(0, strict.size())
    }

    @Test fun `preview keeps control regions and mixed object fragments literal`() {
        val xml = preview(paragraph("{#if true}") + paragraph("Inside {name}") + paragraph("{#else}") + paragraph("Else {name}") +
            paragraph("{/if}") + paragraph("Outside {name}") + "<w:p><w:r><w:t>{broken</w:t><w:br/><w:t>}</w:t></w:r></w:p>")
        assertTrue(xml.contains("Inside {name}"), xml)
        assertTrue(xml.contains("Else {name}"), xml)
        assertTrue(visibleText(xml).contains("Outside Ada"), xml)
        assertTrue(xml.contains("[LITERAL BLOCK]"), xml)
        assertTrue(xml.contains("{broken"), xml)
        assertTrue(xml.contains("<w:br"), xml)
    }

    @Test fun `inspection fallback does not evaluate source and production denies it`() {
        val output = ByteArrayOutputStream()
        val diagnostic = Diagnostic(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.NATIVE_DOCX_PROFILE_UNSUPPORTED,
            part = "word/document.xml").withDocumentLocation("paragraph:1")
        DocxProvider().inspectionCopy(GenerationRequest(source(paragraph("{name}")).inputStream(), requirements), output, development(), diagnostic, false)
        val xml = SyntheticDocx.unzip(output.toByteArray()).getValue("word/document.xml").decodeToString()
        assertTrue(xml.contains("[ERROR 1]"), xml)
        assertTrue(xml.contains("{name}"), xml)
        assertThrows(GenerationException::class.java) {
            DocxProvider().inspectionCopy(GenerationRequest(source(paragraph("{name}")).inputStream(), requirements),
                ByteArrayOutputStream(), GenerationContext(), diagnostic, false)
        }
    }

    @Test fun `preview never guesses mismatched control blocks and can resume after balanced inline regions`() {
        val balanced = preview(paragraph("{#if true}Inside {name}{/if}") + paragraph("After {name}"))
        assertTrue(balanced.contains("Inside {name}"), balanced)
        assertTrue(visibleText(balanced).contains("After Ada"), balanced)
        val mismatch = preview(paragraph("{#if true}") + paragraph("{/each}") + paragraph("After {name}"))
        assertFalse(visibleText(mismatch).contains("After Ada"), mismatch)
        assertTrue(mismatch.contains("After {name}"), mismatch)
        val orphan = preview(paragraph("{#else}") + paragraph("After {name}"))
        assertTrue(orphan.contains("After {name}"), orphan)
    }

    @Test fun `preview control detection ignores field instructions and retains visible field results`() {
        val xml = preview("<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText>{#if false}</w:instrText></w:r>" +
            "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>Visible {name}</w:t></w:r>" +
            "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>" + paragraph("After {name}"))
        assertFalse(xml.contains("[LITERAL BLOCK]"), xml)
        assertTrue(visibleText(xml).contains("Visible Ada"), xml)
        assertTrue(visibleText(xml).contains("After Ada"), xml)
        assertTrue(xml.contains("{#if false}"), xml)
    }

    @Test fun `inspection report retains unmapped rejection reasons without an invented marker`() {
        val diagnostic = Diagnostic(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.NATIVE_DOCX_PROFILE_UNSUPPORTED,
            part = "word/settings.xml").withSourceContext(DocumentSourceContext("Unsupported compatibility setting", null, null, ""))
        val output = ByteArrayOutputStream()
        DocxProvider().inspectionCopy(GenerationRequest(source(paragraph("Original text")).inputStream(), requirements), output, development(), diagnostic, false)
        val xml = SyntheticDocx.unzip(output.toByteArray()).getValue("word/document.xml").decodeToString()
        assertTrue(xml.contains("Unsupported compatibility setting"), xml)
        assertTrue(xml.contains("No reliable XML node coordinate"), xml)
        assertFalse(xml.contains("[ERROR 1]"), xml)
        assertFalse(xml.contains("XML path: null"), xml)
    }

}

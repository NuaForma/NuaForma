package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class TemplateDiagnosticCopyTest {
    private val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX))
    private val data = mapOf("name" to "Ada", "customer" to mapOf("name" to "Example"), "reference" to "REF", "empty" to "")
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
        DocxProvider().inspectionCopy(GenerationRequest(source(body).inputStream(), requirements, data),
            output, development(), null, true)
        return SyntheticDocx.unzip(output.toByteArray()).getValue("word/document.xml").decodeToString()
    }

    @Test fun `passive markers inside a visible expression do not cause preview errors`() {
        val body = "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Before {na</w:t></w:r>" +
            "<w:bookmarkStart w:id=\"70\" w:name=\"inside\"/><w:proofErr w:type=\"spellStart\"/>" +
            "<w:r><w:t>me</w:t><w:lastRenderedPageBreak/><w:t>}</w:t></w:r>" +
            "<w:proofErr w:type=\"spellEnd\"/><w:bookmarkEnd w:id=\"70\"/>" +
            "<w:r><w:rPr><w:i/></w:rPr><w:t> after</w:t></w:r></w:p>"
        val xml = preview(body)
        assertTrue(visibleText(xml).contains("Before Ada after"), xml)
        assertFalse(xml.contains("[ERROR"), xml)
        for (marker in listOf("bookmarkStart", "bookmarkEnd", "proofErr", "lastRenderedPageBreak"))
            assertTrue(xml.contains(marker), xml)
        assertTrue(xml.contains("w:b"), xml)
        assertTrue(xml.contains("w:i"), xml)
        val output = ByteArrayOutputStream()
        val events = mutableListOf<String>()
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT, null, GenerationLogSink { events.add(it.text) }))
        try { DocxProvider().generate(GenerationRequest(source(body).inputStream(), requirements, data), output, context) }
        catch (error: GenerationException) { fail<Nothing>(error.diagnostics.joinToString { it.rejectionReason?.name.orEmpty() } + events.joinToString(), error) }
        val generated = SyntheticDocx.unzip(output.toByteArray()).getValue("word/document.xml").decodeToString()
        assertTrue(visibleText(generated).contains("Before Ada after"), generated)
        assertTrue(generated.contains("w:name=\"inside\""), generated)
    }

    @Test fun `passive marker positions retain bookmark order around literals replacements and empty values`() {
        val expression = "{name}"
        for (position in 1 until expression.length) {
            val metadata = "<w:bookmarkStart w:id=\"71\" w:name=\"position\"/><w:bookmarkEnd w:id=\"71\"/>" +
                "<w:proofErr w:type=\"gramStart\"/><w:proofErr w:type=\"gramEnd\"/>"
            val body = "<w:p><w:r><w:t>Prefix ${expression.take(position)}</w:t></w:r>" + metadata +
                "<w:r><w:t>${expression.drop(position)} suffix</w:t></w:r></w:p>"
            val xml = preview(body)
            assertTrue(visibleText(xml).contains("Prefix Ada suffix"), xml)
            assertFalse(xml.contains("[ERROR"), xml)
            assertTrue(xml.indexOf("bookmarkStart") < xml.indexOf("bookmarkEnd"), xml)
            assertEquals(1, Regex("w:bookmarkStart ").findAll(xml).count(), xml)
        }
        val body = "<w:p><w:r><w:t>Prefix {empty</w:t></w:r><w:bookmarkStart w:id=\"72\" w:name=\"empty\"/>" +
            "<w:r><w:t>}</w:t></w:r><w:bookmarkEnd w:id=\"72\"/><w:r><w:t> suffix</w:t></w:r></w:p>"
        val xml = preview(body)
        assertTrue(visibleText(xml).contains("Prefix  suffix"), xml)
        assertTrue(xml.indexOf("bookmarkStart") < xml.indexOf("bookmarkEnd"), xml)
    }

    @Test fun `passive structural markers work but cannot detach bookmark ranges`() {
        val opening = "<w:p><w:r><w:t>{#i</w:t></w:r><w:proofErr w:type=\"spellStart\"/>" +
            "<w:r><w:t>f true}</w:t></w:r><w:proofErr w:type=\"spellEnd\"/></w:p>"
        val body = opening + paragraph("Hello {name}") + paragraph("{/if}")
        val output = ByteArrayOutputStream()
        DocxProvider().generate(GenerationRequest(source(body).inputStream(), requirements, data), output, development())
        val result = SyntheticDocx.unzip(output.toByteArray()).getValue("word/document.xml").decodeToString()
        assertEquals("Hello Ada", visibleText(result))
        val crossing = body.replace("<w:proofErr w:type=\"spellStart\"/>", "<w:bookmarkStart w:id=\"73\" w:name=\"crossing\"/>")
            .replace("<w:proofErr w:type=\"spellEnd\"/>", "") + "<w:p><w:bookmarkEnd w:id=\"73\"/></w:p>"
        val rejected = ByteArrayOutputStream()
        assertThrows(GenerationException::class.java) {
            DocxProvider().generate(GenerationRequest(source(crossing).inputStream(), requirements), rejected, development())
        }
        assertEquals(0, rejected.size())
    }

    @Test fun `real breaks fields hidden text and paragraph boundaries still interrupt markers`() {
        val prefix = "<w:r><w:t>{na</w:t></w:r>"
        val suffix = "<w:r><w:t>me}</w:t></w:r>"
        for (body in listOf(
            "<w:p><w:r><w:t>{na</w:t><w:br/><w:t>me}</w:t></w:r></w:p>",
            "<w:p><w:r><w:t>{na</w:t><w:tab/><w:t>me}</w:t></w:r></w:p>",
            "<w:p>$prefix<w:r><w:rPr><w:vanish/></w:rPr><w:t>ignored</w:t></w:r>$suffix</w:p>",
            "<w:p>$prefix<w:fldSimple w:instr=\"PAGE\"><w:r><w:t>1</w:t></w:r></w:fldSimple>$suffix</w:p>",
            "<w:p>$prefix</w:p><w:p>$suffix</w:p>",
            "<w:p>$prefix<w:hyperlink>$suffix</w:hyperlink></w:p>"
        )) {
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                DocxProvider().generate(GenerationRequest(source(body).inputStream(), requirements, data), output, development())
            }
            assertEquals(DiagnosticCodes.INVALID_TEMPLATE, error.diagnostics.first().code)
            assertEquals(0, output.size())
        }
    }

    @Test fun `cached page hints never create visible content for empty replacements`() {
        val xml = preview("<w:p><w:r><w:t>{empty</w:t><w:lastRenderedPageBreak/><w:t>}</w:t></w:r></w:p>")
        assertFalse(xml.contains("lastRenderedPageBreak"), xml)
        assertFalse(xml.contains("[ERROR"), xml)
        assertFalse(visibleText(xml).contains("{empty}"), xml)
    }

    @Test fun `preview does not duplicate a supplied original parser diagnostic`() {
        val input = source(paragraph("Prefix {broken"))
        val diagnostic = copy(input).first
        val output = ByteArrayOutputStream()
        DocxProvider().inspectionCopy(GenerationRequest(input.inputStream(), requirements, data), output, development(), diagnostic, true)
        val text = visibleText(SyntheticDocx.unzip(output.toByteArray()).getValue("word/document.xml").decodeToString())
        assertEquals(1, Regex("\\[ERROR 1]").findAll(text).count(), text)
        assertTrue(text.contains("Prefix [ERROR 1] {broken"), text)
        assertTrue(text.contains("UNCLOSED_TEMPLATE_MARKER"), text)
    }

    @Test fun `preview marks only the first failure at its actual brace and reports the reason`() {
        val xml = preview(paragraph("Good {name}") + paragraph("Long valid prefix {broken") +
            paragraph("Another {broken") + paragraph("After {name}"))
        val text = visibleText(xml)
        assertTrue(text.contains("Long valid prefix [ERROR 1] {broken"), text)
        assertEquals(1, Regex("\\[ERROR 1]").findAll(text).count(), text)
        assertFalse(text.contains("[ERROR]"), text)
        assertTrue(text.contains("UNCLOSED_TEMPLATE_MARKER"), text)
        assertTrue(text.contains("word/document.xml"), text)
        assertTrue(text.contains("paragraph:2"), text)
        assertTrue(text.contains("After Ada"), text)
        assertTrue(text.contains("Another {broken"), text)
    }

    @Test fun `preview preserves malformed spans and evaluates only independent valid text`() {
        val xml = preview(paragraph("Hello {name}") + paragraph("Broken {name") + paragraph("After {name}"))
        assertTrue(visibleText(xml).contains("Hello Ada"), xml)
        assertTrue(visibleText(xml).contains("Broken [ERROR 1] {name"), xml)
        assertTrue(xml.contains("[ERROR 1]"), xml)
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

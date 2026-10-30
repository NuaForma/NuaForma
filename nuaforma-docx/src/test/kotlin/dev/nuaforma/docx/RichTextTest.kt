package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class RichTextTest {
    private val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX))
    private val marker = "{description | richText(\"rtf\")}"
    private fun run(text: String, properties: String = "") = "<w:r><w:rPr>$properties</w:rPr><w:t xml:space=\"preserve\">$text</w:t></w:r>"
    private fun paragraph(text: String, properties: String = "") = "<w:p><w:pPr>$properties</w:pPr>$text</w:p>"
    private fun source(body: String, styles: String = "", header: String = "", footer: String = ""): ByteArray {
        val parts = SyntheticDocx.parts()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
            .replace(Regex("<w:body>[\\s\\S]*</w:body>"), "<w:body>$body</w:body>").toByteArray()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("</w:styles>", "$styles</w:styles>").toByteArray()
        parts["word/header1.xml"] = "<w:hdr xmlns:w=\"${SyntheticDocx.W}\">$header</w:hdr>".toByteArray()
        parts["word/footer1.xml"] = "<w:ftr xmlns:w=\"${SyntheticDocx.W}\">$footer</w:ftr>".toByteArray()
        return SyntheticDocx.zip(parts)
    }
    private fun generate(bytes: ByteArray, data: Map<String, Any?> = emptyMap(), provider: DocxProvider = DocxProvider()): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        provider.generate(GenerationRequest(bytes.inputStream(), requirements, data), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        return SyntheticDocx.unzip(output.toByteArray())
    }
    private fun element(bytes: ByteArray): Element = DocumentBuilderFactory.newDefaultInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }.newDocumentBuilder().parse(bytes.inputStream()).documentElement
    private fun descendants(root: Element, name: String): List<Element> = root.getElementsByTagNameNS(SyntheticDocx.W, name)
        .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
    private fun texts(parts: Map<String, ByteArray>, part: String = "word/document.xml") = descendants(element(parts.getValue(part)), "p")
        .map { paragraph -> descendants(paragraph, "t").joinToString("") { it.textContent } }
    private fun data(value: Any?) = mapOf("description" to value)

    @Test fun `rich text splits real paragraphs and joins prefixes and suffixes without changing source styles`() {
        val bytes = source(paragraph(run("Prefix ", "<w:i/>") + run("{description | rich", "<w:b/><w:color w:val=\"123456\"/>") +
            run("Text(\"rtf\")}") + run(" suffix", "<w:u w:val=\"single\"/>"), "<w:jc w:val=\"right\"/>") + paragraph(run("After")))
        val result = generate(bytes, data("{\\rtf1 First\\par\\b0\\i Second\\par Third}"))
        assertEquals(listOf("Prefix First", "Second", "Third suffix", "After"), texts(result))
        val paragraphs = descendants(element(result.getValue("word/document.xml")), "p")
        assertEquals(3, paragraphs.take(3).count { descendants(it, "jc").single().getAttributeNS(SyntheticDocx.W, "val") == "right" })
        val first = descendants(paragraphs[0], "r").single { descendants(it, "t").any { text -> text.textContent == "First" } }
        assertEquals(1, descendants(first, "b").size)
        assertEquals("123456", descendants(first, "color").single().getAttributeNS(SyntheticDocx.W, "val"))
        val second = descendants(paragraphs[1], "r").single()
        assertEquals("false", descendants(second, "b").single().getAttributeNS(SyntheticDocx.W, "val"))
        assertEquals(1, descendants(second, "i").size)
    }
    @Test fun `boundary trims are independent and internal blank paragraphs are never removed`() {
        val rtf = "{\\rtf1\\par First\\par\\par Last\\par\\par}"
        for (start in listOf(false, true)) for (end in listOf(false, true)) {
            val text = "{description | richText(\"rtf\", $start, $end)}"
            val expected = (if (start) emptyList() else listOf("")) + listOf("First", "", "Last") + (if (end) emptyList() else listOf(""))
            assertEquals(expected, texts(generate(source(paragraph(run(text))), data(rtf))), "$start/$end")
        }
        assertEquals(listOf(""), texts(generate(source(paragraph(run("{description | richText(\"rtf\", false, false)}"))), data("{\\rtf1\\par}"))))
        assertEquals(listOf(" Before X After "), texts(generate(source(paragraph(run(" Before $marker After "))), data("{\\rtf1 X}"))))
    }
    @Test fun `empty null missing and all-trimmed values remove only the marker and otherwise empty paragraph`() {
        val bytes = source(paragraph(run("Before")) + paragraph(run(marker)) + paragraph(run("After")))
        for (value in listOf(null, "", "{\\rtf1}", "{\\rtf1\\par\\par}")) {
            assertEquals(listOf("Before", "After"), texts(generate(bytes, data(value))), "$value")
        }
        assertEquals(listOf("Before", "After"), texts(generate(bytes)))
        assertEquals(listOf("Prefix  suffix"), texts(generate(source(paragraph(run("Prefix $marker suffix"))))))
        assertEquals(listOf("  "), texts(generate(source(paragraph(run(" $marker "))))))
        assertThrows(GenerationException::class.java) { generate(source(paragraph(run("{description}")))) }
    }
    @Test fun `intentional spaces and inherited visible whitespace decoration survive boundary trimming`() {
        val result = generate(source(paragraph(run(marker, "<w:u w:val=\"single\"/>"))), data("{\\rtf1  \\par}"))
        assertEquals(listOf(" "), texts(result))
        assertEquals("single", descendants(element(result.getValue("word/document.xml")), "u").single().getAttributeNS(SyntheticDocx.W, "val"))
    }
    @Test fun `empty insertion preserves section ownership and a structurally valid blank body`() {
        val section = "<w:sectPr><w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/></w:sectPr>"
        val preserved = generate(source(paragraph(run(marker), section) + paragraph(run("After"))))
        assertEquals(listOf("", "After"), texts(preserved))
        assertEquals(1, descendants(element(preserved.getValue("word/document.xml")), "sectPr").size)
        assertEquals(listOf(""), texts(generate(source(paragraph(run(marker))))))
    }
    @Test fun `multiple markers structural loops conditionals and inserted braces are evaluated exactly once`() {
        val bytes = source(paragraph(run("{#each rows as row}")) + paragraph(run("{#if row.enabled}")) +
            paragraph(run("A {row.rtf | richText(\"rtf\")} B {tail | richText(\"rtf\")} C")) + paragraph(run("{/if}")) + paragraph(run("{/each}")))
        val result = generate(bytes, mapOf("rows" to listOf(mapOf("enabled" to true, "rtf" to "{\\rtf1 \\{secret\\}\\par TWO}"),
            mapOf("enabled" to false, "rtf" to "{\\rtf1\\object BLOCKED}")), "tail" to "{\\rtf1 END}", "secret" to "MUST_NOT_EVALUATE"))
        assertEquals(listOf("A {secret}", "TWO B END C"), texts(result))
        assertFalse(result.getValue("word/document.xml").decodeToString().contains("MUST_NOT_EVALUATE"))
        assertEquals(listOf("{secret}literal"), texts(generate(source(paragraph(run(marker))), data("{\\rtf1 \\{secret\\}\\tab literal}"))))
    }
    @Test fun `malformed repeated rich data retains the original template paragraph in development diagnostics`() {
        val table = "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr><w:tc><w:tcPr/>" +
            paragraph(run("{row.rtf | richText(\"rtf\")}")) + "</w:tc></w:tr></w:tbl>"
        val template = source(paragraph(run("Before")) + paragraph(run("{#each rows as row}")) + table + paragraph(run("{/each}")))
        val error = assertThrows(GenerationException::class.java) { generate(template, mapOf("rows" to listOf(
            mapOf("rtf" to "{\\rtf1 valid}"), mapOf("rtf" to "{\\rtf1\\object PRIVATE}")))) }
        assertEquals("word/document.xml", error.diagnostics.single().part)
        assertEquals("paragraph:3", error.diagnostics.single().documentLocation)
        assertTrue(error.diagnostics.single().location!!.startsWith("rtf:"))
    }
    @Test fun `tabs unicode fonts colors underline and strikethrough become actual WML properties`() {
        val rtf = "{\\rtf1{\\fonttbl{\\f0\\fnil DejaVu Sans;}}{\\colortbl;\\red20\\green50\\blue90;}\\f0\\fs28\\cf1\\b\\i\\ul\\strike A\\tab\\u1046?}"
        val result = generate(source(paragraph(run(marker))), data(rtf))
        val root = element(result.getValue("word/document.xml"))
        assertEquals(listOf("AЖ"), texts(result))
        assertEquals(1, descendants(root, "tab").size)
        for (name in listOf("b", "bCs", "i", "iCs", "strike", "u")) assertEquals(1, descendants(root, name).size, name)
        assertEquals("DejaVu Sans", descendants(root, "rFonts").single().getAttributeNS(SyntheticDocx.W, "ascii"))
        assertEquals("28", descendants(root, "sz").single().getAttributeNS(SyntheticDocx.W, "val"))
        assertEquals("14325a", descendants(root, "color").single().getAttributeNS(SyntheticDocx.W, "val"))
    }
    @Test fun `table cells headers footers and supported legacy text boxes receive real paragraph siblings`() {
        val table = "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr><w:tc><w:tcPr/>${paragraph(run(marker))}</w:tc></w:tr></w:tbl>"
        val textbox = "<w:p><w:r><w:pict><v:shape xmlns:v=\"urn:schemas-microsoft-com:vml\" id=\"rich-box\"><v:textbox><w:txbxContent>${paragraph(run(marker))}</w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>"
        val original = SyntheticDocx.parts().getValue("word/document.xml").decodeToString()
        val start = original.lastIndexOf("<w:p><w:r><w:drawing>", original.indexOf("name=\"Modern text box\""))
        val closing = "</w:drawing></w:r></w:p>"
        val modern = original.substring(start, original.indexOf(closing, start) + closing.length).replace(
            Regex("<w:txbxContent>[\\s\\S]*?</w:txbxContent>"), "<w:txbxContent>${paragraph(run(marker))}</w:txbxContent>")
        val result = generate(source(table + textbox + modern, header = paragraph(run(marker)), footer = paragraph(run(marker))), data("{\\rtf1 First\\par Second}"))
        val body = element(result.getValue("word/document.xml"))
        assertEquals(listOf("First", "Second"), descendants(body, "tc").single().let { descendants(it, "p").map { p -> descendants(p, "t").joinToString("") { t -> t.textContent } } })
        val boxes = descendants(body, "txbxContent")
        assertEquals(2, boxes.size)
        boxes.forEach { box -> assertEquals(listOf("First", "Second"), descendants(box, "p").map { p -> descendants(p, "t").joinToString("") { it.textContent } }) }
        assertEquals(listOf("First", "Second"), texts(result, "word/header1.xml"))
        assertEquals(listOf("First", "Second"), texts(result, "word/footer1.xml"))
        val empty = generate(source(table), data(null))
        assertEquals(1, descendants(descendants(element(empty.getValue("word/document.xml")), "tc").single(), "p").size)
    }
    @Test fun `block insertion rejects surrounding text while inline placement remains allowed`() {
        val block = "{description | richText(\"rtf\", true, true, false)}"
        assertEquals(listOf("One", "Two"), texts(generate(source(paragraph(run(block))), data("{\\rtf1 One\\par Two}"))))
        val out = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) { DocxProvider().generate(
            GenerationRequest(source(paragraph(run("before $block"))).inputStream(), requirements, data("{\\rtf1 One}")), out, GenerationContext()) }
        assertEquals(RejectionReason.UNSUPPORTED_RICH_TEXT_PLACEMENT, error.diagnostics.single().rejectionReason)
        assertEquals(0, out.size())
    }
    @Test fun `range objects hyperlinks section frame and inherited numbering boundaries fail explicitly`() {
        val numbered = "<w:style w:type=\"paragraph\" w:styleId=\"Numbered\"><w:name w:val=\"Numbered\"/><w:pPr><w:numPr><w:numId w:val=\"7\"/></w:numPr></w:pPr></w:style>"
        val cases = listOf(
            paragraph("<w:bookmarkStart w:id=\"900\" w:name=\"rich\"/>" + run(marker) + "<w:bookmarkEnd w:id=\"900\"/>"),
            paragraph("<w:hyperlink xmlns:r=\"${SyntheticDocx.R}\" r:id=\"link\">${run(marker)}</w:hyperlink>"),
            paragraph(run(marker), "<w:sectPr/>"), paragraph(run(marker), "<w:framePr w:w=\"2000\"/>"),
            paragraph(run(marker), "<w:pStyle w:val=\"Numbered\"/>"))
        for (body in cases) {
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) { DocxProvider().generate(GenerationRequest(source(body, numbered).inputStream(), requirements,
                data("{\\rtf1 One\\par Two}")), output, GenerationContext()) }
            assertEquals(RejectionReason.UNSUPPORTED_RICH_TEXT_PLACEMENT, error.diagnostics.single().rejectionReason)
            assertEquals(0, output.size())
        }
    }
    @Test fun `page-start heading and inherited numbering ownership is not multiplied by paragraph splitting`() {
        val styles = "<w:style w:type=\"paragraph\" w:styleId=\"PageStart\"><w:name w:val=\"PageStart\"/><w:pPr><w:pageBreakBefore/></w:pPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Chapter\"><w:name w:val=\"Chapter\"/><w:pPr><w:outlineLvl w:val=\"0\"/></w:pPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"List\"><w:name w:val=\"List\"/><w:pPr><w:numPr><w:numId w:val=\"1\"/></w:numPr></w:pPr></w:style>"
        for (properties in listOf("<w:pageBreakBefore/>", "<w:outlineLvl w:val=\"0\"/>", "<w:pStyle w:val=\"PageStart\"/>", "<w:pStyle w:val=\"Chapter\"/>")) {
            assertEquals(RejectionReason.UNSUPPORTED_RICH_TEXT_PLACEMENT, assertThrows(GenerationException::class.java) {
                generate(source(paragraph(run(marker), properties), styles), data("{\\rtf1 One\\par Two}"))
            }.diagnostics.single().rejectionReason)
        }
        for (properties in listOf("<w:pStyle w:val=\"PageStart\"/><w:pageBreakBefore w:val=\"false\"/>",
            "<w:pStyle w:val=\"Chapter\"/><w:outlineLvl w:val=\"9\"/>", "<w:pStyle w:val=\"List\"/><w:numPr><w:numId w:val=\"0\"/></w:numPr>")) {
            assertEquals(listOf("One", "Two"), texts(generate(source(paragraph(run(marker), properties), styles), data("{\\rtf1 One\\par Two}"))))
        }
    }
    @Test fun `source property amplification is rejected before cloning or publishing`() {
        val output = ByteArrayOutputStream()
        val bytes = source(paragraph(run(marker, "<w:rStyle w:val=\"${"x".repeat(20_000)}\"/>")))
        val error = assertThrows(GenerationException::class.java) { DocxProvider().generate(GenerationRequest(bytes.inputStream(), requirements,
            data("{\\rtf1 text}")), output, GenerationContext()) }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, error.diagnostics.single().code)
        assertEquals(0, output.size())
    }
    @Test fun `hidden source remains literal and cannot activate the new pipe`() {
        val bytes = source(paragraph(run("{broken | richText(", "<w:vanish/>")) + paragraph(run(marker)))
        val result = generate(bytes, data("{\\rtf1 visible}"))
        assertEquals(listOf("{broken | richText(", "visible"), texts(result))
    }
    @Test fun `formats input types and nullable flags fail explicitly without output`() {
        val expressions = listOf("{description | richText()}", "{description | richText(\"html\")}",
            "{description | richText(\"rtf\", null)}", "{description | richText(\"rtf\", \"true\")}")
        for (expression in expressions) {
            val output = ByteArrayOutputStream()
            assertThrows(GenerationException::class.java) { DocxProvider().generate(GenerationRequest(source(paragraph(run(expression))).inputStream(),
                requirements, data("{\\rtf1 text}")), output, GenerationContext()) }
            assertEquals(0, output.size())
        }
        assertThrows(GenerationException::class.java) { generate(source(paragraph(run(marker))), data(123)) }
    }
    @Test fun `custom opted-in metadata survives generator snapshots without relaxing other values`() {
        val pipe = object : ContentPipe {
            override val definition = ContentPipeDefinition("optionalContent", "Synthetic optional content", emptyList(),
                listOf(ContentPipeSupport(DocumentFormat.DOCX, DocumentFormat.DOCX)), "Null inserts a literal sentinel", emptySet(), true)
            override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult {
                assertNull(value); return ContentResult.Text("NONE")
            }
        }
        val provider = DocxProvider(DeploymentFonts.configured(), listOf(pipe))
        assertTrue(provider.contentPipes().single { it.name == "optionalContent" }.missingAsNull)
        assertEquals(listOf("NONE"), texts(generate(source(paragraph(run("{absent | optionalContent()}"))), provider = provider)))
        assertFalse(provider.contentPipes().single { it.name == "image" }.missingAsNull)
    }
    @Test fun `inserted font families are validated after binding and resources are read only once`() {
        val family = "Libre Barcode 39"
        val font = requireNotNull(javaClass.getResourceAsStream("/fonts/LibreBarcode39-Regular.ttf")).use { it.readBytes() }
        val parts = SyntheticDocx.unzip(source(paragraph(run(marker)))).mapValues { (name, value) ->
            if (name.endsWith(".xml")) value.decodeToString().replace("Arial", family).toByteArray() else value
        }
        val template = SyntheticDocx.zip(parts)
        for (mode in listOf("validate", "embed")) for (available in listOf(false, true)) {
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val output = ByteArrayOutputStream()
            val request = GenerationRequest(template.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.DOCX, mapOf("fontMode" to mode, "fonts" to listOf(mapOf("family" to family, "resource" to "face"))))),
                data("{\\rtf1{\\fonttbl{\\f0 ${if (available) family else "Unsupplied Family"};}}\\f0 A}"),
                mapOf("face" to ResourceSource { reads.incrementAndGet(); font.inputStream() }))
            if (available) {
                DocxProvider().generate(request, output, GenerationContext())
                assertEquals(mode == "embed", SyntheticDocx.unzip(output.toByteArray()).keys.any { it.startsWith("word/fonts/") })
            } else {
                val error = assertThrows(GenerationException::class.java) { DocxProvider().generate(request, output, GenerationContext()) }
                assertEquals(RejectionReason.A_DECLARED_TEMPLATE_FONT_FAMILY_HAS_NO_SUPPLIED_REGULAR, error.diagnostics.single().rejectionReason)
                assertEquals(0, output.size())
            }
            assertEquals(1, reads.get(), "$mode/$available")
        }
    }
    @Test fun `shared provider isolates concurrent successful malformed and subsequent requests`() {
        val provider = DocxProvider()
        val bytes = source(paragraph(run(marker)))
        val pool = Executors.newFixedThreadPool(3)
        try {
            val results = pool.invokeAll((0 until 9).map { index -> Callable {
                if (index % 3 == 0) assertThrows(GenerationException::class.java) { generate(bytes, data("{\\rtf1\\object TENANT_$index}"), provider) }
                else assertEquals(listOf("TENANT_$index"), texts(generate(bytes, data("{\\rtf1 TENANT_$index}"), provider)))
            } })
            results.forEach { it.get() }
            assertEquals(listOf("CLEAN"), texts(generate(bytes, data("{\\rtf1 CLEAN}"), provider)))
        } finally { pool.shutdownNow() }
    }
    @Test fun `a request cancelled inside rich binding publishes no bytes and leaves the shared provider reusable`() {
        val provider = DocxProvider()
        val template = source(paragraph(run(marker)))
        val armed = java.util.concurrent.atomic.AtomicBoolean()
        val checks = java.util.concurrent.atomic.AtomicInteger()
        val logging = GenerationLogging(LoggingMode.DEVELOPMENT, "rich-cancel", GenerationLogSink { if (it.code == "PIPE_RESULT") armed.set(true) })
        val context = GenerationContext(GenerationLimits(), CancellationToken { armed.get() && checks.incrementAndGet() > 8 }, null,
            java.time.Clock.systemUTC(), logging)
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) { provider.generate(GenerationRequest(template.inputStream(), requirements,
            data("{\\rtf1 " + "CANCELLED_PAYLOAD".repeat(2000) + "}")), output, context) }
        assertEquals(DiagnosticCodes.CANCELLED, error.diagnostics.single().code)
        assertTrue(armed.get()); assertEquals(0, output.size())
        assertEquals(listOf("AFTER"), texts(generate(template, data("{\\rtf1 AFTER}"), provider)))
    }
}

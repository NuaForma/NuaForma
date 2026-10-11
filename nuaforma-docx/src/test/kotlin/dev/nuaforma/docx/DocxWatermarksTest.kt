package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class DocxWatermarksTest {
    private val mark = TextWatermark("CONCEPT", "#127F87", 0.3, -45.0)
    private fun generate(parts: Map<String, ByteArray>, watermark: TextWatermark? = mark,
        context: GenerationContext = GenerationContext()): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        DocxProvider().generate(GenerationRequest(SyntheticDocx.zip(parts).inputStream(),
            GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX),
                GenerationOptions(watermark = watermark)), mapOf("customer" to mapOf("name" to "Ada"), "reference" to "NF-001")), output, context)
        return SyntheticDocx.unzip(output.toByteArray())
    }

    private fun variants(): MutableMap<String, ByteArray> = SyntheticDocx.parts().apply {
        this["word/settings.xml"] = getValue("word/settings.xml").decodeToString().replace("</w:settings>", "<w:evenAndOddHeaders/></w:settings>").toByteArray()
        for ((index, label) in listOf(2 to "First page header", 3 to "Even page header")) {
            this["word/header$index.xml"] = getValue("word/header1.xml").decodeToString().replace("NuaForma header", label).toByteArray()
            this["[Content_Types].xml"] = getValue("[Content_Types].xml").decodeToString().replace("</Types>",
                """<Override PartName="/word/header$index.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/></Types>""").toByteArray()
            this["word/_rels/document.xml.rels"] = getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>",
                """<Relationship Id="header$index" Type="${SyntheticDocx.R}/header" Target="header$index.xml"/></Relationships>""").toByteArray()
        }
        fun page(text: String) = """<w:p><w:r><w:br w:type="page"/></w:r></w:p><w:p><w:r><w:t>$text</w:t></w:r></w:p>"""
        val default = """<w:headerReference w:type="default" r:id="r3"/>"""
        val source = getValue("word/document.xml").decodeToString()
            .replace("<w:p><w:pPr><w:sectPr>", page("Portrait even page") + page("Portrait ordinary page") + "<w:p><w:pPr><w:sectPr>")
            .replaceFirst(default, default + """<w:headerReference w:type="first" r:id="header2"/><w:headerReference w:type="even" r:id="header3"/>""")
            .replace("<w:sectPr>$default<w:footerReference", page("Landscape even page") + "<w:sectPr>$default<w:footerReference")
            .replace("</w:sectPr>", "<w:titlePg/></w:sectPr>")
        this["word/document.xml"] = source.toByteArray()
    }

    private fun count(parts: Map<String, ByteArray>) = parts.filterKeys { it.matches(Regex("word/.*header.*xml")) }.values.sumOf {
        Regex("<v:shape\\s[^>]*id=\"NFWatermark").findAll(it.decodeToString()).count()
    }

    @Test
    fun `decorate active inherited header kinds once and preserve every non-header part`() {
        val parts = variants()
        val plain = generate(parts, null)
        val result = generate(parts)
        assertEquals(3, count(result))
        assertEquals(plain.keys, result.keys)
        for ((name, bytes) in plain) {
            if (name.matches(Regex("word/header[123]\\.xml"))) continue
            if (name.endsWith(".xml") || name.endsWith(".rels")) assertEquals(SyntheticDocx.semantic(bytes), SyntheticDocx.semantic(result.getValue(name)), name)
            else assertArrayEquals(bytes, result.getValue(name), name)
        }
        for (index in 1..3) {
            val xml = result.getValue("word/header$index.xml").decodeToString()
            assertTrue(xml.contains("CONCEPT")); assertTrue(xml.contains("#127F87"))
            assertTrue(xml.contains("rotation:315")); assertTrue(xml.contains("opacity=\"0.3\""))
            assertTrue(xml.contains("mso-position-vertical-relative:page"))
            assertTrue(xml.contains("Ada"))
            val factory = DocumentBuilderFactory.newDefaultInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
            val parsed = factory.newDocumentBuilder().parse(xml.byteInputStream())
            val shapes = parsed.getElementsByTagNameNS("urn:schemas-microsoft-com:vml", "shape")
            val run = shapes.item(0).parentNode.parentNode
            run.parentNode.removeChild(run)
            val restored = ByteArrayOutputStream()
            TransformerFactory.newDefaultInstance().newTransformer().transform(DOMSource(parsed), StreamResult(restored))
            assertEquals(SyntheticDocx.semantic(plain.getValue("word/header$index.xml")), SyntheticDocx.semantic(restored.toByteArray()))
        }
    }

    @Test
    fun `create missing header without touching footers and keep literal watermark text`() {
        val parts = SyntheticDocx.parts().apply {
            this["word/document.xml"] = getValue("word/document.xml").decodeToString()
                .replace("""<w:headerReference w:type="default" r:id="r3"/>""", "").toByteArray()
        }
        val result = generate(parts, TextWatermark("CONCEPT & <draft> {customer.name}", "#FF0000", 0.0, 90.0))
        assertEquals(1, count(result))
        val xml = result.entries.single { it.key.startsWith("word/nf-header-") }.value.decodeToString()
        assertTrue(xml.contains("{customer.name}")); assertTrue(xml.contains("&amp;")); assertTrue(xml.contains("&lt;draft&gt;"))
        assertTrue(xml.contains("rotation:90")); assertTrue(xml.contains("opacity=\"0\""))
        val again = generate(result)
        assertEquals(2, count(again))
        assertEquals(SyntheticDocx.semantic(result.getValue("word/footer1.xml")), SyntheticDocx.semantic(again.getValue("word/footer1.xml")))
        assertEquals(1, count(generate(result, null)))
    }

    @Test
    fun `invalid XML text and output limit fail without successful output`() {
        val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX),
            GenerationOptions(watermark = TextWatermark("invalid\u0001")))
        assertEquals(AssessmentStatus.UNSUPPORTED, DocxProvider().assess(requirements).status)
        assertThrows(GenerationException::class.java) { generate(variants(), context = GenerationContext(limits = GenerationLimits(maxOutputBytes = 50))) }
        assertThrows(GenerationException::class.java) { generate(variants(), context = GenerationContext(cancellation = CancellationToken { true })) }
        val unsafe = SyntheticDocx.parts().apply {
            this["word/document.xml"] = getValue("word/document.xml").decodeToString()
                .replace("""<w:headerReference w:type="default" r:id="r3"/>""", "")
                .replace("w:header=\"720\"", "w:header=\"1440\"").toByteArray()
        }
        assertEquals(DiagnosticCodes.UNSUPPORTED_OPTION, assertThrows(GenerationException::class.java) { generate(unsafe) }.diagnostics.single().code)
    }

    @Test
    fun `build reproducible multi-section watermark visual references`() {
        val directory = Path.of("build/watermark-reference")
        Files.createDirectories(directory)
        val source = variants()
        val expected = source.mapValues { (name, bytes) -> if (name.endsWith(".xml")) bytes.decodeToString()
            .replace("{customer.</w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t>name}", "Ada</w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t>")
            .replace("{customer.</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>name}", "Ada</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>")
            .replace("{customer.</w:t></w:r><w:r><w:t>name}", "Ada</w:t></w:r><w:r><w:t>")
            .replace("{customer.name}", "Ada").replace("{reference}", "NF-001").toByteArray() else bytes }
        Files.write(directory.resolve("expected.docx"), SyntheticDocx.zip(expected))
        for ((name, watermark) in listOf("plain" to null, "concept" to mark,
            "vertical" to TextWatermark("REVIEW", "#C02040", 0.6, 90.0),
            "transparent" to TextWatermark("HIDDEN", "#000000", 0.0, 0.0),
            "opaque" to TextWatermark("OPAQUE", "#127F87", 1.0, 0.0))) {
            Files.write(directory.resolve("$name.docx"), SyntheticDocx.zip(generate(source, watermark)))
        }
        for (kind in listOf("missing", "empty")) {
            val fixture = SyntheticDocx.parts().apply {
                if (kind == "missing") this["word/document.xml"] = getValue("word/document.xml").decodeToString()
                    .replace("""<w:headerReference w:type="default" r:id="r3"/>""", "").toByteArray()
                else this["word/header1.xml"] = """<w:hdr xmlns:w="${SyntheticDocx.W}"><w:p/></w:hdr>""".toByteArray()
            }
            for ((suffix, watermark) in listOf("plain" to null, "concept" to mark, "transparent" to TextWatermark("HIDDEN", opacity = 0.0))) {
                Files.write(directory.resolve("$kind-$suffix.docx"), SyntheticDocx.zip(generate(fixture, watermark)))
            }
        }
    }
}

package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReadException
import dev.nuaforma.pdf.*
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.*

class WordFloatingBandsTest {
    @TempDir lateinit var temporary: Path

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun context() = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))

    private fun parts(bytes: ByteArray): MutableMap<String, ByteArray> = linkedMapOf<String, ByteArray>().also { result ->
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes() }
        }
    }

    private fun zip(parts: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> parts.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    private fun anchor(inline: String, x: Int, y: Int, id: Int): String {
        val content = inline.substringAfter('>').substringBeforeLast("</wp:inline>")
            .replace(Regex("<wp:docPr id=\"[^\"]+\""), "<wp:docPr id=\"$id\"")
            .replace("<wp:docPr", "<wp:wrapNone/><wp:docPr")
        return """<wp:anchor distT="0" distB="0" distL="0" distR="0" simplePos="0" relativeHeight="0" behindDoc="0" locked="0" layoutInCell="1" allowOverlap="1"><wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="page"><wp:posOffset>${x * 12700}</wp:posOffset></wp:positionH><wp:positionV relativeFrom="page"><wp:posOffset>${y * 12700}</wp:posOffset></wp:positionV>$content<wp14:sizeRelH xmlns:wp14="http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing" relativeFrom="margin"><wp14:pctWidth>0</wp14:pctWidth></wp14:sizeRelH><wp14:sizeRelV xmlns:wp14="http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing" relativeFrom="margin"><wp14:pctHeight>0</wp14:pctHeight></wp14:sizeRelV></wp:anchor>"""
    }

    private fun source(kind: String): ByteArray {
        val entries = parts(resource("/word-compatibility/$kind/source.docx"))
        val width = if (kind == "letter") 612 else 595
        val height = if (kind == "letter") 792 else 842
        val inline = Regex("<wp:inline[^>]*>.*?</wp:inline>", RegexOption.DOT_MATCHES_ALL)
        for ((band, y) in listOf("header" to 28, "footer" to height - 50)) {
            val name = "word/${band}1.xml"
            entries[name] = inline.replace(entries.getValue(name).decodeToString()) {
                anchor(it.value, width - 140, y, if (band == "header") 1001 else 1002)
            }.toByteArray()
        }
        val bodyImage = inline.find(resource("/word-compatibility/$kind/source.docx").let(::parts).getValue("word/header1.xml").decodeToString())!!.value
        val bodyDrawing = anchor(bodyImage.replace("emfLogo", "bodyLogo"), width - 140, 120, 1003)
        val prefix = "<w:p><w:pPr><w:rPr><w:highlight w:val=\"yellow\"/></w:rPr></w:pPr><w:r><w:drawing>$bodyDrawing</w:drawing></w:r></w:p>"
        entries["word/document.xml"] = entries.getValue("word/document.xml").decodeToString().replace("<w:body>", "<w:body>$prefix").toByteArray()
        entries["word/_rels/document.xml.rels"] = entries.getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>",
            "<Relationship Id=\"bodyLogo\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/header.emf\"/></Relationships>").toByteArray()
        return zip(entries)
    }

    private fun provider(): NativeDocxPdfProvider {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
            NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
        }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { files -> files.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        return NativeDocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }

    private fun standardPdf(bytes: ByteArray, provider: NativeDocxPdfProvider): ByteArray {
        val output = ByteArrayOutputStream()
        provider.generate(GenerationRequest(bytes.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), output, context())
        return output.toByteArray()
    }

    private fun sameRenderedPages(expected: ByteArray, actual: ByteArray) {
        Loader.loadPDF(expected).use { before -> Loader.loadPDF(actual).use { after ->
            assertEquals(before.numberOfPages, after.numberOfPages)
            assertEquals(PDFTextStripper().getText(before), PDFTextStripper().getText(after))
            for (page in 0 until before.numberOfPages) {
                assertImagesMatch(PDFRenderer(before).renderImageWithDPI(page, 96f), PDFRenderer(after).renderImageWithDPI(page, 96f), "Word metadata page ${page + 1}") {
                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                }
            }
        } }
    }

    @Test
    fun `style revision identifiers stay in the original template and cannot change rendered pages`() {
        val provider = provider()
        for (kind in listOf("a4", "letter")) {
            val baseline = source(kind)
            val entries = parts(baseline)
            val styles = entries.getValue("word/styles.xml").decodeToString()
            assertFalse(styles.contains("<w:rsid"))
            entries["word/styles.xml"] = styles.replace("</w:style>", """<w:rsid w:val="3E412d01"/></w:style>""").toByteArray()
            val input = zip(entries)
            val cleaned = ByteArrayOutputStream()
            val report = provider.cleanTemplate(input.inputStream(), cleaned, context())
            assertTrue(report.changes.isEmpty(), report.changes.toString())
            assertArrayEquals(input, cleaned.toByteArray())
            assertArrayEquals(entries.getValue("word/styles.xml"), parts(cleaned.toByteArray()).getValue("word/styles.xml"))
            sameRenderedPages(standardPdf(baseline, provider), standardPdf(cleaned.toByteArray(), provider))
        }
    }

    @Test
    fun `unsupported nested drawing effects identify their element and cleanup keeps body and footer logos`() {
        val baseline = source("a4")
        val provider = provider()
        val expected = standardPdf(baseline, provider)
        val effect = """<a:effectLst><a:outerShdw blurRad="63500" dist="25400" dir="5400000"><a:srgbClr val="000000"/></a:outerShdw></a:effectLst>"""
        for (part in listOf("word/document.xml", "word/footer1.xml")) {
            val entries = parts(baseline)
            val xml = entries.getValue(part).decodeToString()
            assertTrue(xml.contains("</pic:spPr>"))
            entries[part] = xml.replace("</pic:spPr>", "$effect</pic:spPr>").toByteArray()
            val input = zip(entries)
            val error = assertThrows(NativeDocxReadException::class.java) { NativeDocxReader(context()) { _, _, _ -> "regular" }.read(input) }
            assertEquals(part, error.part)
            assertTrue(error.sourceContext.path!!.endsWith("effectLst[1]"), error.sourceContext.path)
            assertTrue(error.sourceContext.explanation.contains("effectLst/outerShdw"), error.sourceContext.explanation)
            assertTrue(error.location!!.startsWith("xml:"))
            val production = assertThrows(NativeDocxReadException::class.java) { NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(input) }
            assertNull(production.sourceContext.path)
            val output = ByteArrayOutputStream()
            assertThrows(GenerationException::class.java) { provider.generate(GenerationRequest(input.inputStream(), GenerationRequirements(
                FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), output, context()) }
            assertEquals(0, output.size())
            val cleaned = ByteArrayOutputStream()
            val report = provider.cleanTemplate(input.inputStream(), cleaned, context())
            assertEquals(1, report.changes.size, report.changes.toString())
            assertEquals(TemplateCleanupAction.REMOVE_ELEMENT, report.changes.single().action)
            assertEquals(part, report.changes.single().part)
            assertTrue(report.changes.single().location!!.endsWith("effectLst[1]"))
            assertFalse(parts(cleaned.toByteArray()).getValue(part).decodeToString().contains("outerShdw"))
            sameRenderedPages(expected, standardPdf(cleaned.toByteArray(), provider))
        }
    }

    @Test
    fun `unsupported image attributes identify the property and cleanup removes only that attribute`() {
        val baseline = source("a4")
        val provider = provider()
        val cases = listOf(Triple("word/document.xml", "<a:xfrm>", "rot"),
            Triple("word/footer1.xml", "<a:xfrm>", "flipH"),
            Triple("word/footer1.xml", "<a:blip ", "link"))
        val expected = standardPdf(baseline, provider)
        for ((part, marker, attribute) in cases) {
            val entries = parts(baseline)
            val xml = entries.getValue(part).decodeToString()
            assertTrue(xml.contains(marker))
            val replacement = if (attribute == "link") """<a:blip r:link="PRIVATE_EXTERNAL_TARGET" """ else """<a:xfrm $attribute="${if (attribute == "flipH") "true" else "60000"}">"""
            entries[part] = xml.replace(marker, replacement).toByteArray()
            val input = zip(entries)
            val error = assertThrows(NativeDocxReadException::class.java) { NativeDocxReader(context()) { _, _, _ -> "regular" }.read(input) }
            assertEquals(part, error.part)
            assertTrue(error.sourceContext.explanation.contains("/@$attribute"), error.sourceContext.explanation)
            assertFalse(error.sourceContext.explanation.contains("PRIVATE_EXTERNAL_TARGET"))
            assertEquals(attribute, error.unsupportedAttributes.single().localPart)
            val cleaned = ByteArrayOutputStream()
            val report = provider.cleanTemplate(input.inputStream(), cleaned, context())
            assertEquals(1, report.changes.size, report.changes.toString())
            assertEquals(TemplateCleanupAction.REMOVE_ATTRIBUTE, report.changes.single().action)
            sameRenderedPages(expected, standardPdf(cleaned.toByteArray(), provider))
        }
    }

    @Test
    fun `unsupported image geometry points at the failed property without losing drawing provenance`() {
        for ((before, after, element) in listOf(
            Triple("""<a:prstGeom prst="rect">""", """<a:prstGeom prst="ellipse">""", "prstGeom"),
            Triple("<a:fillRect/>", """<a:fillRect l="1000"/>""", "fillRect"),
            Triple("""<a:off x="0" y="0"/>""", """<a:off x="12700" y="0"/>""", "off"),
            Triple("""<a:ext cx="1270000" cy="635000"/>""", """<a:ext cx="1270001" cy="635000"/>""", "ext"))) {
            val entries = parts(source("a4"))
            val body = entries.getValue("word/document.xml").decodeToString()
            assertTrue(body.contains(before))
            entries["word/document.xml"] = body.replace(before, after).toByteArray()
            val error = assertThrows(NativeDocxReadException::class.java) { NativeDocxReader(context()) { _, _, _ -> "regular" }.read(zip(entries)) }
            assertEquals("word/document.xml", error.part)
            assertTrue(error.sourceContext.path!!.endsWith("$element[1]"), error.sourceContext.path)
            assertTrue(error.sourceContext.path!!.contains("drawing[1]"))
        }
    }

    @Test
    fun `cleanup removes an optional effect extent as a whole rather than leaving required attributes missing`() {
        val baseline = source("a4")
        val entries = parts(baseline)
        val part = "word/footer1.xml"
        val footer = entries.getValue(part).decodeToString().replace(Regex("<wp:effectExtent\b[^>]*/>"), "")
        entries[part] = footer.replace("<wp:docPr", """<wp:effectExtent l="12700" t="0" r="0" b="0"/><wp:docPr""").toByteArray()
        val input = zip(entries)
        val error = assertThrows(NativeDocxReadException::class.java) { NativeDocxReader(context()) { _, _, _ -> "regular" }.read(input) }
        assertTrue(error.sourceContext.path!!.endsWith("effectExtent[1]"))
        assertTrue(error.sourceContext.explanation.contains("effectExtent/@l"))
        assertTrue(error.unsupportedAttributes.isEmpty())
        val provider = provider()
        val cleaned = ByteArrayOutputStream()
        val report = provider.cleanTemplate(input.inputStream(), cleaned, context())
        assertEquals(1, report.changes.size, report.changes.toString())
        assertEquals(TemplateCleanupAction.REMOVE_ELEMENT, report.changes.single().action)
        assertFalse(parts(cleaned.toByteArray()).getValue(part).decodeToString().contains("effectExtent"))
        sameRenderedPages(standardPdf(baseline, provider), standardPdf(cleaned.toByteArray(), provider))
    }

    @Test
    fun `unwrapped body art cannot cover reserved footnotes or publish a partial PDF`() {
        val entries = parts(source("a4"))
        entries["word/document.xml"] = entries.getValue("word/document.xml").decodeToString()
            .replace("<wp:posOffset>${120 * 12700}</wp:posOffset>", "<wp:posOffset>${760 * 12700}</wp:posOffset>").toByteArray()
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) {
            provider().generate(GenerationRequest(zip(entries).inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), output, context())
        }
        assertTrue(error.diagnostics.any { it.code == DiagnosticCodes.UNSUPPORTED_INPUT })
        assertEquals(0, output.size())
        assertEquals(0L, Files.list(temporary).use { it.count() })
    }

    @Test
    fun `colored numbering marks remain explicit until visible label highlighting is qualified`() {
        for (color in listOf("none", "yellow")) {
            val entries = parts(resource("/own-engine-lists/source.docx"))
            entries["word/document.xml"] = entries.getValue("word/document.xml").decodeToString().replace("<w:numPr>",
                "<w:rPr><w:highlight w:val=\"$color\"/></w:rPr><w:numPr>").toByteArray()
            val read = { NativeDocxReader(context()) { _, _, _ -> "regular" }.read(zip(entries)) }
            if (color == "none") assertDoesNotThrow { read() }
            else assertTrue(assertThrows(NativeDocxReadException::class.java) { read() }.message!!.contains("numbered paragraph marks"))
        }
    }

    @Test
    fun `footer text frame resolves repeated page fields without removing its surrounding story`() {
        val entries = parts(source("a4"))
        val frame = """<w:p><w:r><w:pict xmlns:v="urn:schemas-microsoft-com:vml"><v:rect id="footerBox" style="position:absolute;margin-left:300pt;margin-top:780pt;width:110pt;height:24pt;mso-position-horizontal-relative:page;mso-position-vertical-relative:page;v-text-anchor:top" filled="f" stroked="f"><v:textbox inset="0pt,0pt,0pt,0pt"><w:txbxContent><w:p><w:r><w:t xml:space="preserve">BOX PAGE </w:t></w:r><w:fldSimple w:instr="PAGE"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p></w:txbxContent></v:textbox></v:rect></w:pict></w:r></w:p>"""
        entries["word/footer1.xml"] = entries.getValue("word/footer1.xml").decodeToString().replace("</w:ftr>", frame + "</w:ftr>").toByteArray()
        val source = zip(entries)
        val provider = provider()
        val cleaned = ByteArrayOutputStream()
        val report = provider.cleanTemplate(source.inputStream(), cleaned, context())
        assertTrue(report.changes.isEmpty(), report.changes.joinToString { "${it.part} ${it.location} ${it.reason}" })
        val output = ByteArrayOutputStream()
        provider.generate(GenerationRequest(cleaned.toByteArray().inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), output, context())
        Loader.loadPDF(output.toByteArray()).use { pdf ->
            for (index in 0 until pdf.numberOfPages) {
                val text = PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }.getText(pdf)
                assertTrue(text.contains("BOX PAGE ${index + 1}"), text)
                assertTrue(text.contains("FOOTER logo retained"))
            }
        }
        val folder = Files.createDirectories(Path.of("build/word-floating-bands/frame"))
        Files.write(folder.resolve("source.docx"), source)
        Files.write(folder.resolve("actual.pdf"), output.toByteArray())
    }

    @Test
    fun `floating stories retain archival transparency policy and independent validation`() {
        val provider = provider()
        val source = source("a4")
        for (profile in listOf("pdfa-2b", "pdfa-3b")) {
            val output = ByteArrayOutputStream()
            provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output, context())
            Loader.loadPDF(output.toByteArray()).use { pdf -> assertTrue(PDFTextStripper().getText(pdf).contains("FOOTER logo retained")) }
        }
        val output = ByteArrayOutputStream()
        assertThrows(GenerationException::class.java) {
            provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to "pdfa-1b")))), output, context())
        }
        assertEquals(0, output.size())
    }

    @Test
    fun `passive template cleanup detaches only its reference and keeps literal markers and band relationships`() {
        val entries = parts(source("a4"))
        val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val packageRel = "http://schemas.openxmlformats.org/package/2006/relationships"
        val settings = entries["word/settings.xml"]?.decodeToString() ?: "<w:settings xmlns:w=\"$word\"/>"
        entries["word/settings.xml"] = settings.replace(Regex("/>\\s*$"), "></w:settings>").let { text ->
            val end = text.lastIndexOf("</w:settings>")
            text.substring(0, end) + "<w:attachedTemplate xmlns:r=\"$office\" r:id=\"template\"/>" + text.substring(end)
        }.toByteArray()
        if (!entries.getValue("[Content_Types].xml").decodeToString().contains("/word/settings.xml")) {
            entries["[Content_Types].xml"] = entries.getValue("[Content_Types].xml").decodeToString().replace("</Types>",
                "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/></Types>").toByteArray()
        }
        entries["word/_rels/settings.xml.rels"] = """<Relationships xmlns="$packageRel"><Relationship Id="template" Type="$office/attachedTemplate" TargetMode="External" Target="https://example.invalid/private-template.dotx"/></Relationships>""".toByteArray()
        if (!entries.getValue("word/_rels/document.xml.rels").decodeToString().contains("Target=\"settings.xml\"")) {
            entries["word/_rels/document.xml.rels"] = entries.getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>",
                "<Relationship Id=\"settings\" Type=\"$office/settings\" Target=\"settings.xml\"/></Relationships>").toByteArray()
        }
        entries["word/document.xml"] = entries.getValue("word/document.xml").decodeToString().replace("SIGNED INDENTS", "{customer.name}").toByteArray()
        val cleaned = ByteArrayOutputStream()
        val report = provider().cleanTemplate(zip(entries).inputStream(), cleaned, context())
        val result = parts(cleaned.toByteArray())
        assertFalse(result.getValue("word/settings.xml").decodeToString().contains("attachedTemplate"))
        assertTrue(result.getValue("word/document.xml").decodeToString().contains("{customer.name}"))
        for (name in listOf("word/footer1.xml", "word/header1.xml", "word/_rels/footer1.xml.rels", "word/footnotes.xml")) {
            assertArrayEquals(entries.getValue(name), result.getValue(name), name)
        }
        assertTrue(report.changes.any { it.part == "word/_rels/settings.xml.rels" })
        assertFalse(report.changes.any { it.part == "word/footer1.xml" })
    }

    @Test
    fun `relative sizing and wrapping do not silently broaden the image profile`() {
        for ((old, replacement) in listOf(
            "<wp14:pctWidth>0</wp14:pctWidth>" to "<wp14:pctWidth>100000</wp14:pctWidth>",
            "<wp14:pctHeight>0</wp14:pctHeight>" to "<wp14:pctHeight>-1</wp14:pctHeight>",
            "relativeFrom=\"margin\"><wp14:pctWidth>" to "relativeFrom=\"unknown\"><wp14:pctWidth>",
            "<wp:wrapNone/>" to "<wp:wrapSquare wrapText=\"bothSides\"/>")) {
            val entries = parts(source("a4"))
            entries["word/footer1.xml"] = entries.getValue("word/footer1.xml").decodeToString().replace(old, replacement).toByteArray()
            assertThrows(NativeDocxReadException::class.java) { NativeDocxReader(context()) { _, _, _ -> "regular" }.read(zip(entries)) }
        }
    }

    @Test
    fun `cleanup preserves floating body and band logos paragraph marks and real notes through PDF generation`() {
        val provider = provider()
        for (kind in listOf("a4", "letter")) {
            val source = source(kind)
            val cleaned = ByteArrayOutputStream()
            val report = provider.cleanTemplate(source.inputStream(), cleaned, context())
            assertTrue(report.changes.isEmpty(), report.changes.joinToString { "${it.part} ${it.location} ${it.reason}" })
            assertArrayEquals(source, cleaned.toByteArray())
            val output = ByteArrayOutputStream()
            provider.generate(GenerationRequest(cleaned.toByteArray().inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard"))), emptyMap<String, Any?>()), output, context())
            val folder = Files.createDirectories(Path.of("build/word-floating-bands/$kind"))
            Files.write(folder.resolve("source.docx"), source)
            Files.write(folder.resolve("actual.pdf"), output.toByteArray())
            Loader.loadPDF(output.toByteArray()).use { pdf ->
                val text = PDFTextStripper().getText(pdf)
                assertTrue(text.contains("Een korte toelichting bij de eerste verwijzing."))
                assertTrue(text.contains("END De laatste alinea"))
                assertTrue(pdf.numberOfPages >= 2)
                assertEquals(pdf.numberOfPages, Regex("HEADER logo retained").findAll(text).count())
                assertEquals(pdf.numberOfPages, Regex("FOOTER logo retained").findAll(text).count())
                for (index in 0 until pdf.numberOfPages) {
                    val image = PDFRenderer(pdf).renderImageWithDPI(index, 72f)
                    val x = if (kind == "letter") 612 - 140 else 595 - 140
                    assertEquals(java.awt.Color(16, 112, 176).rgb, image.getRGB(x + 50, 50), "header logo page ${index + 1}")
                    assertEquals(java.awt.Color(16, 112, 176).rgb, image.getRGB(x + 20, image.height - 40), "footer logo page ${index + 1}")
                }
            }
        }
    }

    @Test
    fun `unknown anchor children identify the actual child and cleanup retains footer text and notes`() {
        val entries = parts(source("a4"))
        entries["word/footer1.xml"] = entries.getValue("word/footer1.xml").decodeToString().replace("</wp:anchor>",
            "<wp:unknownEffect/></wp:anchor>").toByteArray()
        val error = assertThrows(NativeDocxReadException::class.java) { NativeDocxReader(context()) { _, _, _ -> "regular" }.read(zip(entries)) }
        assertEquals("word/footer1.xml", error.part)
        assertTrue(error.sourceContext.path!!.endsWith("unknownEffect[1]"))
        val cleaned = ByteArrayOutputStream()
        val report = provider().cleanTemplate(zip(entries).inputStream(), cleaned, context())
        assertFalse(report.changes.any { it.action == TemplateCleanupAction.REMOVE_PART && it.part == "word/footer1.xml" })
        assertTrue(parts(cleaned.toByteArray()).getValue("word/footer1.xml").decodeToString().contains("FOOTER logo retained"))
        assertTrue(parts(cleaned.toByteArray()).containsKey("word/footnotes.xml"))
    }

}

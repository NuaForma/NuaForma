package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.DocxProvider
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfOutputProfile
import dev.nuaforma.pdf.PdfProcessValidator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Source builder for reviewed new golden files; this test never creates or replaces reference resources. */
class NativeRichTextTest {
    @TempDir lateinit var temporary: Path
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun <T> checked(action: () -> T): T = try { action() } catch (error: GenerationException) {
        throw AssertionError(error.diagnostics.joinToString("\n") { "${it.code}: ${it.rejectionReason} ${it.message} part=${it.part} location=${it.location}" }, error)
    }
    private fun provider(): DocxPdfProvider {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
            NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
        }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        return DocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }
    private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun run(text: String, bold: Boolean = false, italic: Boolean = false, underline: Boolean = false,
                    strike: Boolean = false, size: Int = 22, color: String = "000000", rtl: Boolean = false) =
        "<w:r><w:rPr><w:rFonts w:ascii=\"Liberation Sans\" w:hAnsi=\"Liberation Sans\" w:cs=\"Liberation Sans\"/>" +
            "<w:b w:val=\"$bold\"/><w:bCs w:val=\"$bold\"/><w:i w:val=\"$italic\"/><w:iCs w:val=\"$italic\"/>" +
            "<w:u w:val=\"${if (underline) "single" else "none"}\"/><w:strike w:val=\"$strike\"/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/>" +
            "<w:color w:val=\"$color\"/>${if (rtl) "<w:rtl/>" else ""}</w:rPr>" +
            text.split('\t').mapIndexed { index, part -> (if (index == 0) "" else "<w:tab/>") + "<w:t xml:space=\"preserve\">${xml(part)}</w:t>" }.joinToString("") + "</w:r>"
    private fun paragraph(runs: String, rtl: Boolean = false, line: Int = 400) = "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"120\" w:line=\"$line\" w:lineRule=\"exact\"/>" +
        (if (rtl) "<w:bidi/><w:jc w:val=\"left\"/>" else "") + "</w:pPr>$runs</w:p>"
    private fun unicode(text: String) = text.map { character -> when (character) {
        '{', '}', '\\' -> "\\$character"
        else -> if (character.code in 32..126) "$character" else "\\u${character.code.toShort()}?"
    } }.joinToString("")
    private val rtf get() = "{\\rtf1\\ansi\\ansicpg1252{\\fonttbl{\\f0\\fnil Liberation Sans;}}{\\colortbl;\\red20\\green50\\blue90;}\\uc1" +
        "\\par\\f0\\b Bold\\b0, \\i italic\\i0, \\ul underlined\\ulnone, \\strike crossed\\strike0.\\par\\par " +
        "Second: ${unicode("Café Русский {literal}")}\\tab end\\par\\fs28\\cf1 Colored and larger\\plain  base.\\par\\par}"
    private val hebrew = "שלום עולם 123"
    private val data get() = mapOf("description" to rtf, "hebrew" to "{\\rtf1\\uc1 ${unicode(hebrew)}}")

    private fun source(letter: Boolean = false, expected: Boolean = false): ByteArray {
        val parts = PdfFixture.unzip(resource("/own-engine/source.docx")).toMutableMap()
        val document = parts.getValue("word/document.xml").decodeToString()
        val section = Regex("<w:sectPr[\\s\\S]*?</w:sectPr>").find(document)!!.value.let {
            if (letter) it.replace(Regex("<w:pgSz[^>]*/>"), "<w:pgSz w:w=\"12240\" w:h=\"15840\"/>") else it
        }
        val body = paragraph(run("Rich-text qualification", bold = true, size = 34), line = 480) + if (expected) {
            // Independent expected WML: does not call the RTF reader or inspect the generated document.
            paragraph(run("Prefix: ") + run("Bold", bold = true) + run(", ") + run("italic", italic = true) + run(", ") +
                run("underlined", underline = true) + run(", ") + run("crossed", strike = true) + run(".")) + paragraph("") +
                paragraph(run("Second: Café Русский {literal}\tend")) +
                paragraph(run("Colored and larger", size = 28, color = "14325a") + run(" base. :suffix")) +
                paragraph(run(hebrew, rtl = true), rtl = true)
        } else {
            paragraph(run("Prefix: {description | richText(\"rtf\")} :suffix")) +
                paragraph(run("{hebrew | richText(\"rtf\")}", rtl = true), rtl = true)
        }
        parts["word/document.xml"] = document.replace(Regex("<w:body>[\\s\\S]*</w:body>"), "<w:body>$body$section</w:body>").toByteArray()
        return PdfFixture.zip(parts)
    }

    @Test fun `filled rich text qualifies A4 and Letter in every profile and matches its reviewed golden`() {
        val provider = provider()
        val directory = Files.createDirectories(Path.of("build/118-rich-text"))
        Files.write(directory.resolve("template.docx"), source())
        Files.write(directory.resolve("expected.docx"), source(expected = true))
        val generated = ByteArrayOutputStream()
        checked { DocxProvider().generate(GenerationRequest(source().inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.DOCX)), data), generated, GenerationContext()) }
        Files.write(directory.resolve("actual.docx"), generated.toByteArray())
        var golden: java.awt.image.BufferedImage? = null
        for (letter in listOf(false, true)) for (profile in PdfOutputProfile.entries) {
            val output = ByteArrayOutputStream()
            checked {
                provider.generate(GenerationRequest(source(letter).inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                    FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile.id))), data), output, GenerationContext())
            }
            Files.write(directory.resolve("${if (letter) "letter" else "a4"}-${profile.id}.pdf"), output.toByteArray())
            Loader.loadPDF(output.toByteArray()).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                assertEquals(if (letter) 612f else 595.3f, pdf.getPage(0).mediaBox.width, 0.1f)
                assertEquals(if (letter) 792f else 841.9f, pdf.getPage(0).mediaBox.height, 0.1f)
                val text = PDFTextStripper().getText(pdf)
                for (expected in listOf("Prefix: Bold, italic, underlined, crossed.", "Second: Café Русский {literal}", "Colored and larger base. :suffix", hebrew))
                    assertTrue(text.contains(expected), "$expected\n$text")
                assertFalse(text.contains("richText"))
                assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
                if (!letter && profile == PdfOutputProfile.STANDARD) golden = PDFRenderer(pdf).renderImageWithDPI(0, 96f)
            }
        }
        assertEquals(0, Files.list(temporary).use { it.count() })
        ImageIO.write(requireNotNull(golden), "png", directory.resolve("page-1.png").toFile())
        val expected = javaClass.getResourceAsStream("/rich-text/page-1.png")
        assertNotNull(expected, "Review the new build/118-rich-text candidate against the independent expected Word export; never overwrite golden resources")
        expected!!.use { assertImagesMatch(ImageIO.read(it), golden, "Native rich-text page 1") {
            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
        } }
    }
    @Test fun `RTF rejection remains explicit with a data position and never publishes PDF bytes`() {
        val provider = provider()
        for (value in listOf("{\\rtf1\\object PRIVATE_CANARY}", "{\\rtf1\\u-10179?}")) {
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                provider.generate(GenerationRequest(source().inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF)),
                    data + ("description" to value)), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            }
            val diagnostic = error.diagnostics.single()
            assertTrue(diagnostic.rejectionReason in setOf(RejectionReason.INVALID_RICH_TEXT, RejectionReason.UNSUPPORTED_RICH_TEXT))
            assertTrue(diagnostic.location!!.startsWith("rtf:"))
            assertEquals("word/document.xml", diagnostic.part)
            assertTrue(diagnostic.documentLocation!!.startsWith("paragraph:"))
            assertFalse(diagnostic.message.contains("PRIVATE_CANARY"))
            assertEquals(0, output.size())
        }
    }
    @Test fun `capabilities describe the same defaults media type profiles and missing policy as execution`() {
        val definition = provider().contentPipes().single { it.name == "richText" }
        assertTrue(definition.missingAsNull)
        assertEquals(setOf("application/rtf"), definition.contentTypes)
        assertEquals(listOf("format", "trimStart", "trimEnd", "inline"), definition.arguments.map { it.name })
        assertEquals(listOf(true, true, true), definition.arguments.drop(1).map { it.defaultValue })
        assertEquals(PdfOutputProfile.entries.map { it.id }.toSet(), definition.support.single { it.output == DocumentFormat.PDF }.profiles)
        assertFalse(definition.chainable)
    }
    @Test fun `an otherwise empty optional rich-text document produces a valid blank page in every profile`() {
        val parts = PdfFixture.unzip(source()).toMutableMap()
        val original = parts.getValue("word/document.xml").decodeToString()
        val section = Regex("<w:sectPr[\\s\\S]*?</w:sectPr>").find(original)!!.value
        parts["word/document.xml"] = original.replace(Regex("<w:body>[\\s\\S]*</w:body>"),
            "<w:body>${paragraph(run("{description | richText(\"rtf\")}"))}$section</w:body>").toByteArray()
        val template = PdfFixture.zip(parts)
        val provider = provider()
        for (profile in PdfOutputProfile.entries) {
            val output = ByteArrayOutputStream()
            checked { provider.generate(GenerationRequest(template.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile.id)))), output, GenerationContext()) }
            Loader.loadPDF(output.toByteArray()).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                assertEquals("", PDFTextStripper().getText(pdf).trim())
                assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
            }
        }
    }
    @Test fun `a long rich-text fragment paginates without duplicating or dropping internal paragraphs`() {
        val rows = (1..80).map { "Row ${it.toString().padStart(3, '0')} - preserved paragraph" }
        val output = ByteArrayOutputStream()
        checked { provider().generate(GenerationRequest(source().inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF)), data + ("description" to "{\\rtf1 ${rows.joinToString("\\par ")}}")), output, GenerationContext()) }
        Loader.loadPDF(output.toByteArray()).use { pdf ->
            assertTrue(pdf.numberOfPages >= 3)
            val text = PDFTextStripper().getText(pdf)
            rows.forEach { row -> assertEquals(1, Regex(Regex.escape(row)).findAll(text).count(), row) }
            assertTrue(text.contains(" :suffix"))
        }
    }
    @Test fun `rich headers and footers render real paragraph siblings and optional empty bands disappear`() {
        val parts = PdfFixture.unzip(source()).toMutableMap()
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val relationship = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        parts["word/header1.xml"] = "<w:hdr xmlns:w=\"$w\">${paragraph(run("{header | richText(\"rtf\")}"))}</w:hdr>".toByteArray()
        parts["word/footer1.xml"] = "<w:ftr xmlns:w=\"$w\">${paragraph(run("{footer | richText(\"rtf\")}"))}</w:ftr>".toByteArray()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replace("<w:sectPr>",
            "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"richHeader\"/><w:footerReference w:type=\"default\" r:id=\"richFooter\"/>").toByteArray()
        parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>",
            "<Relationship Id=\"richHeader\" Type=\"$relationship/header\" Target=\"header1.xml\"/><Relationship Id=\"richFooter\" Type=\"$relationship/footer\" Target=\"footer1.xml\"/></Relationships>").toByteArray()
        parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString().replace("</Types>",
            "<Override PartName=\"/word/header1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>" +
                "<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/></Types>").toByteArray()
        val template = PdfFixture.zip(parts)
        val provider = provider()
        for (present in listOf(false, true)) {
            val output = ByteArrayOutputStream()
            val values = if (present) data + mapOf("header" to "{\\rtf1\\b HEADER ONE\\par HEADER TWO}", "footer" to "{\\rtf1 FOOTER ONE\\par FOOTER TWO}") else data
            checked { provider.generate(GenerationRequest(template.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF)),
                values), output, GenerationContext()) }
            Loader.loadPDF(output.toByteArray()).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                val text = PDFTextStripper().getText(pdf)
                for (line in listOf("HEADER ONE", "HEADER TWO", "FOOTER ONE", "FOOTER TWO")) assertEquals(present, text.contains(line), text)
            }
        }
    }
}

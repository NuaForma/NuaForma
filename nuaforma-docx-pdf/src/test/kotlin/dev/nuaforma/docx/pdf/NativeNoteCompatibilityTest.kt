package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.*
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

class NativeNoteCompatibilityTest {
    @TempDir lateinit var temporary: Path
    private val word = PdfFixture.W
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val symbols = listOf("*", "\u2020", "\u2021", "\u00a7")

    private fun source(letter: Boolean, automatic: Boolean): ByteArray {
        val parts = PdfFixture.parts()
        parts["word/styles.xml"] = """<w:styles xmlns:w="$word"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:before="0" w:after="0" w:line="320" w:lineRule="exact"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:next w:val="NoteText"/></w:style><w:style w:type="paragraph" w:styleId="Heading"><w:name w:val="Heading"/><w:next w:val="Normal"/><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="NoteText"><w:name w:val="Note Text"/><w:basedOn w:val="Normal"/><w:next w:val="Heading"/><w:pPr><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="exact"/><w:widowControl w:val="0"/></w:pPr><w:rPr><w:sz w:val="18"/></w:rPr></w:style><w:style w:type="character" w:styleId="Reference"><w:name w:val="Reference"/><w:rPr><w:vertAlign w:val="superscript"/></w:rPr></w:style></w:styles>""".toByteArray()
        val body = (1..12).joinToString("") { id ->
            val title = if (id == 1 || id == 7) """<w:p><w:pPr><w:pStyle w:val="Heading"/><w:spacing w:line="560" w:lineRule="exact"/>${if (id == 7) "<w:pageBreakBefore/>" else ""}</w:pPr><w:r><w:t>Chicago notes ${if (id == 1) "one" else "two"}</w:t></w:r></w:p>""" else ""
            title + """<w:p><w:r><w:t>CALL${id.toString().padStart(2, '0')}</w:t></w:r><w:r><w:rPr><w:rStyle w:val="Reference"/></w:rPr><w:footnoteReference w:id="$id"/></w:r><w:r><w:t xml:space="preserve"> body retained.</w:t></w:r></w:p>"""
        }
        parts["word/document.xml"] = """<w:document xmlns:w="$word"><w:body>$body<w:sectPr><w:footnotePr><w:numFmt w:val="chicago"/><w:numRestart w:val="continuous"/></w:footnotePr><w:pgSz w:w="${if (letter) 12240 else 11906}" w:h="${if (letter) 15840 else 16838}"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""".toByteArray()
        val separators = listOf("separator" to -1, "continuationSeparator" to 0).joinToString("") { (type, id) ->
            """<w:footnote w:type="$type" w:id="$id"><w:p><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="${if (automatic) "auto" else "exact"}"/></w:pPr><w:r><w:$type/></w:r></w:p></w:footnote>"""
        }
        val notes = (1..12).joinToString("") { id ->
            """<w:footnote w:id="$id"><w:p><w:pPr><w:pStyle w:val="NoteText"/></w:pPr><w:r><w:rPr><w:rStyle w:val="Reference"/></w:rPr><w:footnoteRef/></w:r><w:r><w:t xml:space="preserve"> NOTE${id.toString().padStart(2, '0')} preserved.</w:t></w:r></w:p></w:footnote>"""
        }
        parts["word/footnotes.xml"] = """<w:footnotes xmlns:w="$word">$separators$notes</w:footnotes>""".toByteArray()
        parts["word/settings.xml"] = """<w:settings xmlns:w="$word"><w:stylePaneSortMethod w:val="0000"/></w:settings>""".toByteArray()
        parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>", """<Relationship Id="notes" Type="$office/footnotes" Target="footnotes.xml"/><Relationship Id="settings" Type="$office/settings" Target="settings.xml"/></Relationships>""").toByteArray()
        parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString().replace("</Types>", """<Override PartName="/word/footnotes.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml"/><Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>""").toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun provider(): DocxPdfProvider {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (face, file) -> NativePdfFont("Liberation Sans", face,
            requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-$file.ttf")).use { it.readBytes() }) }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        return DocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }

    private fun pdf(provider: DocxPdfProvider, input: ByteArray, profile: String) = ByteArrayOutputStream().also { output ->
        try {
            provider.generate(GenerationRequest(input.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        } catch (error: GenerationException) {
            throw AssertionError("$profile: ${error.diagnostics}", error)
        }
    }.toByteArray()

    @Test
    fun `Chicago numbering and existing paragraph styles survive cleanup and all four PDF profiles on A4 and Letter`() {
        val provider = provider()
        val directory = Files.createDirectories(Path.of("build/230-note-compatibility"))
        for (letter in listOf(false, true)) {
            val size = if (letter) "letter" else "a4"
            val input = source(letter, true)
            Files.write(directory.resolve("$size.docx"), input)
            val model = NativeDocxReader(GenerationContext()) { _, bold, _ -> if (bold) "bold" else "regular" }.read(input)
            assertEquals((0..11).map { symbols[it % 4].repeat(it / 4 + 1) }, model.notes.map { it.number.text })
            val paragraphs = model.blocks.filterIsInstance<PdfParagraph>()
            assertEquals("bold", (paragraphs.first().runs.single() as PdfTextRun).style.fontId)
            assertTrue(paragraphs.filter { it.runs.first() is PdfTextRun && (it.runs.first() as PdfTextRun).text.startsWith("CALL") }
                .all { (it.runs.first() as PdfTextRun).style.size == 11f })
            val clean = ByteArrayOutputStream()
            assertTrue(provider.cleanTemplate(input.inputStream(), clean, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(input, clean.toByteArray())
            Loader.loadPDF(pdf(provider, source(letter, false), "standard")).use { reference ->
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val bytes = pdf(provider, input, profile)
                    Files.write(directory.resolve("$size-$profile.pdf"), bytes)
                    Loader.loadPDF(bytes).use { document ->
                        assertEquals(2, document.numberOfPages)
                        assertEquals(if (letter) 612f else 595.3f, document.getPage(0).mediaBox.width, .1f)
                        val text = PDFTextStripper().getText(document)
                        for (id in 1..12) assertEquals(1, Regex("NOTE${id.toString().padStart(2, '0')}(?![0-9])").findAll(text).count())
                        assertTrue(text.contains("\u2020\u2020\u2020") && text.contains("\u2021\u2021\u2021") && text.contains("\u00a7\u00a7\u00a7"))
                        assertTrue(document.documentCatalog.names.dests.names.keys.containsAll((1..12).flatMap { listOf("source-footnote$it", "source-footnote$it-call") }))
                        assertTrue(document.pages.sumOf { it.annotations.size } >= 24)
                        for (page in 0..1) {
                            val raster = PDFRenderer(document).renderImageWithDPI(page, 96f)
                            assertImagesMatch(PDFRenderer(reference).renderImageWithDPI(page, 96f), raster) {
                                pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                            }
                            if (profile == "standard") ImageIO.write(raster, "png", directory.resolve("$size-${page + 1}.png").toFile())
                        }
                    }
                }
            }
        }
        assertEquals(0L, Files.list(temporary).use { it.count() })
    }

    @Test
    fun `continued Chicago footnotes retain every line once and preserve page ownership`() {
        val parts = PdfFixture.unzip(requireNotNull(javaClass.getResourceAsStream("/own-engine-notes/split-note/source.docx")).use { it.readBytes() }).toMutableMap()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replace("w:val=\"decimal\"", "w:val=\"chicago\"").toByteArray()
        parts["word/footnotes.xml"] = parts.getValue("word/footnotes.xml").decodeToString().replace("w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"exact\"", "w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"").toByteArray()
        Loader.loadPDF(pdf(provider(), PdfFixture.zip(parts), "standard")).use { document ->
            assertEquals(2, document.numberOfPages)
            val text = PDFTextStripper().getText(document)
            for (id in 1..38) assertEquals(1, Regex("N2\\.${id.toString().padStart(2, '0')}(?![0-9])").findAll(text).count())
            assertTrue(text.contains("\u2020"))
        }
    }

}

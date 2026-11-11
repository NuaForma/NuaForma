package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReadException
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
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

class NativeWordCompatibilityTest {
    @TempDir lateinit var temporary: Path
    private val word = PdfFixture.W
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun field(code: String, cached: String, form: String = "", properties: String = "") =
        """<w:r>$properties<w:fldChar w:fldCharType="begin">$form</w:fldChar></w:r><w:r><w:instrText xml:space="preserve"> $code </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>$cached</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>"""

    private fun checkbox(checked: Boolean, explicit: Boolean) = field("FORMCHECKBOX", "wrong cached checkbox",
        """<w:ffData><w:name w:val="Check${if (checked) 1 else 2}"/><w:enabled/><w:calcOnExit w:val="0"/><w:checkBox>${if (explicit) """<w:size w:val="24"/>""" else "<w:sizeAuto/>"}<w:default w:val="${if (checked) 0 else 1}"/><w:checked w:val="${if (checked) 1 else 0}"/></w:checkBox></w:ffData>""",
        if (checked) "<w:rPr><w:b/></w:rPr>" else "").replace(
            "<w:r><w:t>wrong cached checkbox</w:t></w:r>",
            """<w:r><w:rPr><w:rFonts w:ascii="Wingdings" w:hAnsi="Wingdings"/></w:rPr><w:sym w:font="Wingdings" w:char="F0FE"/></w:r>""")

    private fun source(letter: Boolean, inline: Boolean, stale: String = "OUTDATED"): ByteArray {
        val parts = PdfFixture.parts()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("DejaVu Sans", "Liberation Sans")
            .replace("</w:docDefaults>", """<w:pPrDefault><w:pPr><w:spacing w:before="0" w:after="0" w:line="400" w:lineRule="exact"/></w:pPr></w:pPrDefault></w:docDefaults>""").toByteArray()
        val start = """<w:bookmarkStart w:id="1" w:name="Target"/>"""
        val end = """<w:bookmarkEnd w:id="1"/>"""
        val target = "<w:r><w:t>Target paragraph</w:t></w:r>"
        val marked = if (inline) "<w:p>$start$target$end</w:p>" else "$start<w:p>$target</w:p>$end"
        val title = field("DOCPROPERTY &quot;Report Title&quot; \\* MERGEFORMAT", stale)
        val assignment = field("SET Choice &quot;M&quot; \\* MERGEFORMAT", "HIDDEN STALE SET")
        val reference = field("REF Choice", "WRONG REF")
        val body = "$marked<w:p><w:r><w:t xml:space=\"preserve\">Property: </w:t></w:r>$title</w:p>" +
            "<w:p>$assignment<w:r><w:t xml:space=\"preserve\">Reference: </w:t></w:r>$reference</w:p>" +
            "<w:p><w:r><w:t xml:space=\"preserve\">Checked: </w:t></w:r>${checkbox(true, false)}<w:r><w:t xml:space=\"preserve\"> Unchecked: </w:t></w:r>${checkbox(false, true)}</w:p>" +
            "<w:p><w:fldSimple w:instr=\"PAGEREF Target \\h\"><w:r><w:t>999</w:t></w:r></w:fldSimple></w:p>" +
            "<w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>Second page retained</w:t></w:r></w:p>"
        parts["word/document.xml"] = """<w:document xmlns:w="$word" xmlns:r="$office"><w:body>$body<w:sectPr><w:headerReference w:type="default" r:id="header"/><w:footerReference w:type="default" r:id="footer"/><w:pgSz w:w="${if (letter) 12240 else 11906}" w:h="${if (letter) 15840 else 16838}"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="360" w:footer="360" w:gutter="0"/></w:sectPr></w:body></w:document>""".toByteArray()
        parts["word/header1.xml"] = """<w:hdr xmlns:w="$word"><w:p>$title</w:p></w:hdr>""".toByteArray()
        parts["word/footer1.xml"] = """<w:ftr xmlns:w="$word"><w:p><w:fldSimple w:instr="PAGE"><w:r><w:t>999</w:t></w:r></w:fldSimple></w:p></w:ftr>""".toByteArray()
        parts["docProps/custom.xml"] = """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"><property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="2" name="Report Title"><vt:lpwstr>Actual report title</vt:lpwstr></property></Properties>""".toByteArray()
        parts["_rels/.rels"] = parts.getValue("_rels/.rels").decodeToString().replace("</Relationships>", """<Relationship Id="properties" Type="$office/custom-properties" Target="docProps/custom.xml"/></Relationships>""").toByteArray()
        val bands = listOf("header", "footer")
        parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>", bands.joinToString("") { """<Relationship Id="$it" Type="$office/$it" Target="${it}1.xml"/>""" } + "</Relationships>").toByteArray()
        val types = bands.joinToString("") { """<Override PartName="/word/${it}1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.$it+xml"/>""" } + """<Override PartName="/docProps/custom.xml" ContentType="application/vnd.openxmlformats-officedocument.custom-properties+xml"/>"""
        parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString().replace("</Types>", "$types</Types>").toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun provider(): DocxPdfProvider {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold").map { (face, file) ->
            NativePdfFont("Liberation Sans", face, requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-$file.ttf")).use { it.readBytes() })
        }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        return DocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }

    private fun pdf(provider: DocxPdfProvider, input: ByteArray, profile: String): ByteArray = ByteArrayOutputStream().also { output ->
        provider.generate(GenerationRequest(input.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output, GenerationContext())
    }.toByteArray()

    @Test
    fun `body bookmarks actual properties SET references and form checkboxes survive every profile on A4 and Letter`() {
        val provider = provider()
        val directory = Files.createDirectories(Path.of("build/228-word-compatibility"))
        for (letter in listOf(false, true)) {
            val size = if (letter) "letter" else "a4"
            val input = source(letter, false)
            Files.write(directory.resolve("$size.docx"), input)
            val cleaned = ByteArrayOutputStream()
            val report = provider.cleanTemplate(input.inputStream(), cleaned, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            assertTrue(report.changes.isEmpty(), report.changes.toString())
            assertArrayEquals(input, cleaned.toByteArray())
            Loader.loadPDF(pdf(provider, source(letter, true, "DIFFERENT STALE VALUE"), "standard")).use { reference ->
                for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                    val bytes = pdf(provider, input, profile)
                    Files.write(directory.resolve("$size-$profile.pdf"), bytes)
                    Loader.loadPDF(bytes).use { actual ->
                        assertEquals(2, actual.numberOfPages)
                        val text = PDFTextStripper().getText(actual)
                        assertTrue(text.contains("Property: Actual report title") && text.contains("Reference: M"), text)
                        assertFalse(text.contains("OUTDATED") || text.contains("WRONG") || text.contains("STALE") || text.contains("cached"), text)
                        assertEquals(0, actual.documentCatalog.names.dests.names.getValue("Target").retrievePageNumber())
                        assertTrue(actual.pages.any { it.annotations.isNotEmpty() })
                        assertEquals(PDFTextStripper().getText(reference), text)
                        for (page in 0..1) {
                            val raster = PDFRenderer(actual).renderImageWithDPI(page, 96f)
                            assertImagesMatch(PDFRenderer(reference).renderImageWithDPI(page, 96f), raster) {
                                pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                            }
                            if (profile == "standard") ImageIO.write(raster, "png", directory.resolve("$size-page-${page + 1}.png").toFile())
                        }
                    }
                }
            }
        }
        assertEquals(0L, Files.list(temporary).use { it.count() })
    }

    @Test
    fun `a text reference to a body-ending bookmark rejects its included paragraph mark instead of losing it`() {
        val parts = PdfFixture.unzip(source(false, false)).toMutableMap()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replace("PAGEREF Target \\h", "REF Target").toByteArray()
        val error = assertThrows(NativeDocxReadException::class.java) {
            NativeDocxReader(GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))) { _, bold, _ -> if (bold) "bold" else "regular" }.read(PdfFixture.zip(parts))
        }
        assertTrue(error.message!!.contains("block-level bookmark ends"), error.message)
        assertEquals("word/document.xml", error.part)
        assertEquals("paragraph:5", error.documentLocation)
        assertTrue(error.sourceContext.path!!.contains("fldSimple"))
    }

    @Test
    fun `static property values retain the existing complex-script field restriction`() {
        val parts = PdfFixture.unzip(source(false, false)).toMutableMap()
        parts["docProps/custom.xml"] = parts.getValue("docProps/custom.xml").decodeToString().replace("Actual report title", "שלום").toByteArray()
        val error = assertThrows(NativeDocxReadException::class.java) {
            NativeDocxReader(GenerationContext()) { _, bold, _ -> if (bold) "bold" else "regular" }.read(PdfFixture.zip(parts))
        }
        assertTrue(error.message!!.contains("field direction profile"))
    }

    @Test
    fun `an empty property keeps the bookmark destination pending for the following visible text`() {
        val parts = PdfFixture.unzip(source(false, false)).toMutableMap()
        parts["docProps/custom.xml"] = parts.getValue("docProps/custom.xml").decodeToString().replace("Actual report title", "").toByteArray()
        val target = "<w:r><w:t>Target paragraph</w:t></w:r>"
        val empty = """<w:fldSimple w:instr='DOCPROPERTY "Report Title"'><w:r><w:rPr><w:rFonts w:ascii="Cache-only Font" w:hAnsi="Cache-only Font"/></w:rPr><w:t>STALE</w:t></w:r></w:fldSimple>"""
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replace(target, empty + target).toByteArray()
        val model = NativeDocxReader(GenerationContext()) { family, bold, _ ->
            require(family == "Liberation Sans") { "An empty field must not request its unused cache font" }
            if (bold) "bold" else "regular"
        }.read(PdfFixture.zip(parts))
        val paragraph = model.blocks.first() as dev.nuaforma.pdf.PdfParagraph
        val text = paragraph.runs.single() as dev.nuaforma.pdf.PdfTextRun
        assertEquals("Target paragraph", text.text)
        assertEquals("Target", text.navigation!!.destinations.single().name)
    }

    @Test
    fun `a missing property in a collapsed complex field identifies its original marker and paragraph`() {
        val parts = PdfFixture.unzip(source(false, false)).toMutableMap()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replace("Report Title", "Missing Property").toByteArray()
        val error = assertThrows(NativeDocxReadException::class.java) {
            NativeDocxReader(GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))) { _, bold, _ -> if (bold) "bold" else "regular" }.read(PdfFixture.zip(parts))
        }
        assertEquals("word/document.xml", error.part)
        assertEquals("paragraph:2", error.documentLocation)
        assertTrue(error.location!!.startsWith("xml:1:"), error.location)
        assertTrue(error.sourceContext.path!!.contains("w:fldChar"), error.sourceContext.toString())
    }

}

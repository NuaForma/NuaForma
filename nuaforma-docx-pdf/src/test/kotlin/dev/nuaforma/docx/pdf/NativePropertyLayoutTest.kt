package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReadException
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
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

class NativePropertyLayoutTest {
    @TempDir lateinit var temporary: Path
    private val word = PdfFixture.W
    private val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun source(letter: Boolean, kind: String): ByteArray {
        val parts = PdfFixture.parts()
        parts["word/styles.xml"] = """<w:styles xmlns:w="$word"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:before="0" w:after="0" w:line="560" w:lineRule="exact"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Big"><w:name w:val="Big"/><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style></w:styles>""".toByteArray()
        val begin = """<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText> DOCPROPERTY &quot;Report Title&quot; \* MERGEFORMAT </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>"""
        val end = """<w:r><w:fldChar w:fldCharType="end"/></w:r>"""
        val body = """<w:p><w:pPr><w:pStyle w:val="Big"/><w:jc w:val="center"/></w:pPr><w:r><w:t xml:space="preserve">BEFORE </w:t></w:r>$begin<w:r><w:t>OLD ONE</w:t></w:r></w:p><w:p><w:pPr><w:jc w:val="left"/></w:pPr><w:r><w:t>OLD TWO</w:t></w:r></w:p><w:p><w:pPr><w:jc w:val="right"/></w:pPr>$end<w:r><w:t xml:space="preserve"> AFTER</w:t></w:r></w:p><w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>Following page retained</w:t></w:r></w:p>"""
        parts["word/document.xml"] = """<w:document xmlns:w="$word"><w:body>$body<w:sectPr><w:pgSz w:w="${if (letter) 12240 else 11906}" w:h="${if (letter) 15840 else 16838}"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""".toByteArray()
        val value = when (kind) {
            "long" -> "First_x000d_\nSecond_x000d_\nThird_x000d_\nFourth_x000d_\nFifth"
            "tail" -> "First_x000d_\nSecond_x000d_\n"
            "empty" -> ""
            else -> "Single actual value"
        }
        parts["docProps/custom.xml"] = """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"><property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="2" name="Report Title"><vt:lpwstr>$value</vt:lpwstr></property></Properties>""".toByteArray()
        parts["_rels/.rels"] = parts.getValue("_rels/.rels").decodeToString().replace("</Relationships>", """<Relationship Id="properties" Type="$rel/custom-properties" Target="docProps/custom.xml"/></Relationships>""").toByteArray()
        parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString().replace("</Types>", """<Override PartName="/docProps/custom.xml" ContentType="application/vnd.openxmlformats-officedocument.custom-properties+xml"/></Types>""").toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun read(input: ByteArray) = NativeDocxReader(GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))) { family, bold, _ ->
        require(family == "Liberation Sans")
        if (bold) "bold" else "regular"
    }.read(input)

    @Test
    fun `actual property paragraphs change count while preserving Word paragraph and font ownership`() {
        for ((kind, count) in listOf("long" to 6, "tail" to 4, "single" to 2, "empty" to 2)) {
            val model = read(source(false, kind))
            val paragraphs = model.blocks.filterIsInstance<PdfParagraph>()
            assertEquals(count, paragraphs.size, kind)
            val text = paragraphs.flatMap { it.runs }.filterIsInstance<PdfTextRun>().joinToString("") { it.text }
            assertTrue(text.startsWith("BEFORE ") && text.contains(" AFTER") && text.endsWith("Following page retained"), text)
            assertFalse(text.contains("OLD"))
            if (kind == "long") {
                assertTrue((paragraphs[0].runs.last() as PdfTextRun).style.fontId == "bold")
                assertTrue((paragraphs[2].runs.single() as PdfTextRun).style.fontId == "regular")
                assertEquals("Fifth AFTER", paragraphs[4].runs.filterIsInstance<PdfTextRun>().joinToString("") { it.text })
            }
        }
    }

    @Test
    fun `unsafe cache content and mixed run formatting are not hidden by property replacement`() {
        for ((old, replacement) in listOf(
            "<w:t>OLD TWO</w:t>" to "<w:drawing/>",
            "<w:t>OLD TWO</w:t>" to "<w:rPr><w:i/></w:rPr><w:t>OLD TWO</w:t>",
            "w:fldCharType=\"begin\"" to "w:fldCharType=\"begin\" w:fldLock=\"true\"")) {
            val parts = PdfFixture.unzip(source(false, "long")).toMutableMap()
            parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replace(old, replacement).toByteArray()
            assertThrows(NativeDocxReadException::class.java) { read(PdfFixture.zip(parts)) }
        }
    }

    @Test
    fun `real provider retains multiline properties and cleanup in every PDF profile on A4 and Letter`() {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold").map { (face, name) -> NativePdfFont("Liberation Sans", face,
            requireNotNull(javaClass.getResourceAsStream("/own-engine-fonts/LiberationSans-$name.ttf")).use { it.readBytes() }) }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        val provider = DocxPdfProvider(NativePdfConfiguration(fonts, validator))
        val directory = Files.createDirectories(Path.of("build/229-property-paragraphs"))
        for (letter in listOf(false, true)) for (kind in listOf("long", "tail")) {
            val name = "${if (letter) "letter" else "a4"}-$kind"
            val input = source(letter, kind)
            Files.write(directory.resolve("$name.docx"), input)
            val cleaned = ByteArrayOutputStream()
            val report = provider.cleanTemplate(input.inputStream(), cleaned, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
            assertTrue(report.changes.isEmpty(), report.changes.toString())
            assertArrayEquals(input, cleaned.toByteArray())
            for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
                val output = ByteArrayOutputStream()
                provider.generate(GenerationRequest(input.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                    FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), output, GenerationContext())
                Files.write(directory.resolve("$name-$profile.pdf"), output.toByteArray())
                Loader.loadPDF(output.toByteArray()).use { pdf ->
                    assertEquals(2, pdf.numberOfPages)
                    val text = PDFTextStripper().getText(pdf)
                    assertTrue(text.contains("First") && text.contains("Second") && text.contains("AFTER"), text)
                    assertFalse(text.contains("OLD") || text.contains("_x000"), text)
                    if (profile == "standard") for (page in 0..1) ImageIO.write(PDFRenderer(pdf).renderImageWithDPI(page, 96f), "png", directory.resolve("$name-${page + 1}.png").toFile())
                }
            }
        }
        assertEquals(0L, Files.list(temporary).use { it.count() })
    }

}

package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReadException
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativePageNumberGalleryTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `nested gallery controls retain page fields and the page band's printed formatting`() {
        val support = NativeWordFeatureSupport(temporary)
        val before = support.generate(template(support, wrapped = false), "standard")
        val after = support.generate(template(support, wrapped = true), "standard")
        Loader.loadPDF(before).use { control ->
            Loader.loadPDF(after).use { wrapped ->
                assertEquals(2, control.numberOfPages)
                assertEquals(control.numberOfPages, wrapped.numberOfPages)
                for (index in 1..2) {
                    val text = PDFTextStripper().apply { startPage = index; endPage = index }.getText(wrapped)
                    assertTrue(text.contains("HEADER_PAGE $index"), text)
                    assertTrue(text.contains("FOOTER_PAGE $index"), text)
                    assertImagesMatch(PDFRenderer(control).renderImageWithDPI(index - 1, 96f),
                        PDFRenderer(wrapped).renderImageWithDPI(index - 1, 96f)) {
                        pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                    }
                }
            }
        }
    }

    @Test
    fun `data bindings and unrelated control galleries do not flatten`() {
        val support = NativeWordFeatureSupport(temporary)
        support.rejectChanged(source, "word/footer1.xml", "sdt") {
            changedBand(it, "ftr", "Bottom", "FOOTER_PAGE", true)
                .replaceFirst("</w:sdtPr>", """<w:dataBinding w:xpath="/root"/></w:sdtPr>""")
        }
        support.rejectChanged(source, "word/footer1.xml", "sdt") {
            changedBand(it, "ftr", "Bottom", "FOOTER_PAGE", true)
                .replaceFirst("<w:noProof/>", """<w:noProof w:val="bad"/>""")
        }
        support.rejectChanged(source, "word/header1.xml", "sdt") {
            changedBand(it, "hdr", "Top", "HEADER_PAGE", true).replace("Page Numbers (Top of Page)", "Watermarks")
        }
    }

    @Test
    fun `source inspection validates proofing metadata before unwrapping a page number gallery`() {
        val support = NativeWordFeatureSupport(temporary)
        val qualified = template(support, wrapped = true)
        fun read(bytes: ByteArray) = NativeDocxReader(GenerationContext()) { _, _, _ -> "font" }
            .read(bytes, inspectImages = true)
        assertTrue(read(qualified).blocks.isNotEmpty())

        val parts = PdfFixture.unzip(qualified)
        val footer = "word/footer1.xml"
        val malformed = parts.getValue(footer).decodeToString()
            .replaceFirst("<w:noProof/>", """<w:noProof w:val="bad"/>""")
        val invalid = PdfFixture.zip(parts + (footer to malformed.toByteArray()))
        val failure = assertThrows(NativeDocxReadException::class.java) { read(invalid) }
        assertEquals(footer, failure.part)
    }

    private fun template(support: NativeWordFeatureSupport, wrapped: Boolean): ByteArray {
        val parts = PdfFixture.unzip(support.bytes(source)).toMutableMap()
        val document = parts.getValue("word/document.xml").decodeToString()
        val body = paragraph("<w:r><w:t>BODY_FIRST</w:t></w:r>") + paragraph("""<w:r><w:br w:type="page"/></w:r>""") +
            paragraph("<w:r><w:t>BODY_SECOND</w:t></w:r>")
        parts["word/document.xml"] = (document.substringBefore("<w:body>") +
            "<w:body>$body<w:sectPr>" + document.substringAfter("<w:sectPr>")).toByteArray()
        val header = "word/header1.xml"
        val footer = "word/footer1.xml"
        parts[header] = changedBand(parts.getValue(header).decodeToString(), "hdr", "Top", "HEADER_PAGE", wrapped).toByteArray()
        parts[footer] = changedBand(parts.getValue(footer).decodeToString(), "ftr", "Bottom", "FOOTER_PAGE", wrapped).toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun changedBand(xml: String, root: String, position: String, prefix: String, wrapped: Boolean): String {
        val opening = requireNotNull(Regex("(.*?<w:$root\\b[^>]*>)", RegexOption.DOT_MATCHES_ALL).find(xml)).value
        val runs = """<w:r><w:t xml:space="preserve">$prefix </w:t></w:r><w:r><w:fldChar w:fldCharType="begin"/></w:r>""" +
            """<w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>""" +
            """<w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>"""
        val line = paragraph(if (wrapped) gallery(runs, position, 1) else runs)
        val contents = if (wrapped) gallery(gallery(line, position, 2), position, 3) else line
        return opening + contents + "</w:$root>"
    }

    private fun gallery(content: String, position: String, id: Int): String =
        """<w:sdt><w:sdtPr><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial"/><w:sz w:val="40"/><w:szCs w:val="40"/><w:noProof w:val="off"/></w:rPr>""" +
            """<w:id w:val="$id"/><w:docPartObj><w:docPartGallery w:val="Page Numbers ($position of Page)"/><w:docPartUnique/></w:docPartObj>""" +
            "</w:sdtPr><w:sdtEndPr><w:rPr><w:noProof/></w:rPr></w:sdtEndPr><w:sdtContent>$content</w:sdtContent></w:sdt>"

    private fun paragraph(content: String): String =
        """<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/></w:pPr>$content</w:p>"""

    private companion object {
        const val source = "/soft-line-breaks/table-bands/a4/source.docx"
    }
}

package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeRepeatedPageFieldFormattingTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `repeated page fields use their code style and recompute both cached and uncached values`() {
        val support = NativeWordFeatureSupport(temporary)
        val full = generate(support, styled, styled)
        for (candidate in listOf(generate(support, styled, regular), generate(support, styled, null),
            generate(support, regular, styled, merge = true), generate(support, styled, null, merge = true))) {
            Loader.loadPDF(full).use { control ->
                Loader.loadPDF(candidate).use { actual ->
                    assertEquals(2, actual.numberOfPages)
                    for (page in 1..2) {
                        val text = PDFTextStripper().apply { startPage = page; endPage = page }.getText(actual)
                        assertTrue(text.contains("HEADERCOUNT $page TOTAL 2"), text)
                        assertTrue(text.contains("FOOTERCOUNT $page TOTAL 2"), text)
                        assertImagesMatch(PDFRenderer(control).renderImageWithDPI(page - 1, 96f),
                            PDFRenderer(actual).renderImageWithDPI(page - 1, 96f)) {
                            pixelTolerance = 0.0
                            allowedDifferentPixelsPercent = 0.0
                            threshold = 1
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `an uncached non-page-count field still reports its source marker`() {
        val support = NativeWordFeatureSupport(temporary)
        support.rejectChanged(source, "word/footer1.xml", "fldChar") {
            band(it, "ftr", "FOOTERCOUNT", regular, null, "").replace(" NUMPAGES ", " REF UNKNOWN ")
        }
    }

    private fun generate(support: NativeWordFeatureSupport, codeStyle: String, cacheStyle: String?, merge: Boolean = false): ByteArray {
        val parts = PdfFixture.unzip(support.bytes(source)).toMutableMap()
        val xml = parts.getValue("word/document.xml").decodeToString()
        val body = """<w:p><w:r><w:t>FIRST_BODY_PAGE</w:t></w:r></w:p>
            <w:p><w:r><w:br w:type="page"/></w:r></w:p><w:p><w:r><w:t>SECOND_BODY_PAGE</w:t></w:r></w:p>"""
        parts["word/document.xml"] = (xml.substringBefore("<w:body>") + "<w:body>$body<w:sectPr>" + xml.substringAfter("<w:sectPr>")).toByteArray()
        val mode = if (merge) " \\* MERGEFORMAT" else ""
        for ((part, root, prefix) in listOf(Triple("word/header1.xml", "hdr", "HEADERCOUNT"), Triple("word/footer1.xml", "ftr", "FOOTERCOUNT"))) {
            parts[part] = band(parts.getValue(part).decodeToString(), root, prefix, codeStyle, cacheStyle, mode).toByteArray()
        }
        return support.generate(PdfFixture.zip(parts), "standard")
    }

    private fun band(original: String, root: String, prefix: String, codeStyle: String, cacheStyle: String?, mode: String): String {
        val start = requireNotNull(Regex("(.*?<w:$root\\b[^>]*>)", RegexOption.DOT_MATCHES_ALL).find(original)).value

        fun count(name: String): String {
            fun control(type: String) = """<w:r>$codeStyle<w:fldChar w:fldCharType="$type"/></w:r>"""
            val cache = if (cacheStyle == null) "" else control("separate") + "<w:r>$cacheStyle<w:t>9</w:t></w:r>"
            return control("begin") + """<w:r>$codeStyle<w:instrText xml:space="preserve"> $name$mode </w:instrText></w:r>""" +
                cache + control("end")
        }

        return start + """<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="360" w:lineRule="exact"/></w:pPr>""" +
            """<w:r>$regular<w:t xml:space="preserve">$prefix </w:t></w:r>""" + count("PAGE") +
            """<w:r>$regular<w:t xml:space="preserve"> TOTAL </w:t></w:r>""" + count("NUMPAGES") + "</w:p></w:$root>"
    }

    private companion object {
        const val source = "/soft-line-breaks/table-bands/a4/source.docx"
        const val styled = """<w:rPr><w:b/><w:sz w:val="16"/><w:szCs w:val="16"/></w:rPr>"""
        const val regular = """<w:rPr><w:sz w:val="22"/><w:szCs w:val="22"/></w:rPr>"""
    }
}

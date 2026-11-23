package dev.nuaforma.docx.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeSoftHyphenTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `Word soft hyphens are printed only at a chosen line break`() {
        val support = NativeWordFeatureSupport(temporary)
        val original = PdfFixture.unzip(support.bytes(source))
        val document = original.getValue(part).decodeToString()
        val cases = listOf(
            110 to listOf("BEFORE COMPAT-", "IBILITYALGORITHM"),
            160 to listOf("BEFORE COMPATIBILITY-", "ALGORITHM"),
            220 to listOf("BEFORE COMPATIBILITYALGORITHM"),
        )
        for ((width, expected) in cases) {
            val changed = PdfFixture.zip(original + (part to document(document, width).toByteArray()))
            Loader.loadPDF(support.generate(changed, "standard")).use {
                val text = PDFTextStripper().apply { sortByPosition = true }.getText(it)
                assertEquals(expected, text.lineSequence().map(String::trim).filter {
                    it.startsWith("BEFORE") || it.startsWith("IBILITY") || it == "ALGORITHM"
                }.toList(), "$width points")
            }
        }
    }

    @Test
    fun `a paragraph containing just a soft hyphen retains its blank line`() {
        val support = NativeWordFeatureSupport(temporary)
        val original = PdfFixture.unzip(support.bytes(source))
        val document = original.getValue(part).decodeToString()

        fun positions(control: String): Map<String, NativeWordFeatureSupport.Position> {
            fun paragraph(content: String) =
                """<w:p><w:pPr><w:spacing w:line="240" w:lineRule="auto"/></w:pPr>$content</w:p>"""
            val body = paragraph("<w:r><w:t>BEFORE_EMPTY</w:t></w:r>") + paragraph(control) +
                paragraph("<w:r><w:t>AFTER_EMPTY</w:t></w:r>")
            val rebuilt = document.substringBefore("<w:body>") + "<w:body>$body<w:sectPr>" + document.substringAfter("<w:sectPr>")
            val input = PdfFixture.zip(original + (part to rebuilt.toByteArray()))
            return Loader.loadPDF(support.generate(input, "standard")).use {
                support.positions(it, Regex("BEFORE_EMPTY|AFTER_EMPTY"))
            }
        }

        val expected = positions("")
        assertEquals(2, expected.size)
        assertEquals(expected, positions("<w:r><w:softHyphen/></w:r>"))
    }

    @Test
    fun `malformed discretionary hyphens retain their source location`() {
        NativeWordFeatureSupport(temporary).rejectChanged(source, part, "softHyphen") {
            document(it, 110).replaceFirst("<w:softHyphen/>", """<w:softHyphen w:val="0"/>""")
        }
    }

    private fun document(source: String, width: Int): String {
        val section = source.substringAfter("<w:sectPr>")
        val page = requireNotNull(Regex("<w:pgSz[^>]+>").find(section)).value
        val edges = requireNotNull(Regex("<w:pgMar[^>]+>").find(section)).value

        fun points(xml: String, name: String): Int =
            requireNotNull(Regex("w:$name=\"(\\d+)\"").find(xml)).groupValues[1].toInt()

        val rightIndent = points(page, "w") - points(edges, "left") - points(edges, "right") - width * 20
        val paragraph = """<w:p><w:pPr><w:ind w:right="$rightIndent"/></w:pPr>
            <w:r><w:rPr><w:sz w:val="22"/></w:rPr><w:t xml:space="preserve">BEFORE COMPAT</w:t></w:r>
            <w:r><w:rPr><w:sz w:val="22"/></w:rPr><w:softHyphen/><w:t>IBILITY</w:t><w:softHyphen/></w:r>
            <w:r><w:rPr><w:sz w:val="22"/></w:rPr><w:t>ALGORITHM</w:t></w:r>
            </w:p>""".trimIndent()
        return source.substringBefore("<w:body>") + "<w:body>$paragraph<w:sectPr>$section"
    }

    private companion object {
        const val part = "word/document.xml"
        const val source = "/floating-drawings/control/a4/source.docx"
    }
}

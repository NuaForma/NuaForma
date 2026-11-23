package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeHeaderParagraphFrameTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `the first header frame uses the header origin before paragraph spacing`() {
        val support = NativeWordFeatureSupport(temporary)
        val relative = support.bytes(fixture)
        val absolute = rewrite(relative) { header ->
            header.replace("""relativeFrom="paragraph"><wp:posOffset>101600""",
                """relativeFrom="page"><wp:posOffset>330200""")
        }
        Loader.loadPDF(support.generate(relative, "standard")).use { actual ->
            Loader.loadPDF(support.generate(absolute, "standard")).use { expected ->
                assertEquals(1, actual.numberOfPages)
                assertEquals(1, expected.numberOfPages)
                assertImagesMatch(PDFRenderer(expected).renderImageWithDPI(0, 96f), PDFRenderer(actual).renderImageWithDPI(0, 96f)) {
                    pixelTolerance = 0.0
                    allowedDifferentPixelsPercent = 0.0
                    threshold = 1
                }
            }
        }
    }

    @Test
    fun `later header anchors remain explicitly rejected`() {
        val support = NativeWordFeatureSupport(temporary)
        val secondParagraph = rewrite(support.bytes(fixture)) { it.replaceFirst("<w:p>", "<w:p/><w:p>") }
        val output = ByteArrayOutputStream()
        val failure = assertThrows(GenerationException::class.java) {
            support.generate(secondParagraph, "standard", output)
        }
        assertEquals(0, output.size())
        val diagnostic = failure.diagnostics.single()
        assertEquals(part, diagnostic.part)
        assertTrue(diagnostic.sourceContext?.path.orEmpty().contains("positionV"))
    }

    private fun rewrite(source: ByteArray, change: (String) -> String): ByteArray {
        val parts = PdfFixture.unzip(source).toMutableMap()
        val original = parts.getValue(part).decodeToString()
        val changed = change(original)
        check(changed != original)
        parts[part] = changed.toByteArray()
        return PdfFixture.zip(parts)
    }

    private companion object {
        const val fixture = "/header-paragraph-frame/a4-relative.docx"
        const val part = "word/header7.xml"
    }
}

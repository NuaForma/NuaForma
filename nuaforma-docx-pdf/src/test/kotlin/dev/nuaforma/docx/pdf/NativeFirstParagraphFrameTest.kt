package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeFirstParagraphFrameTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `first paragraph anchor and body width follow the Word frame coordinates`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes("/floating-drawings/control/$paper/source.docx")).toMutableMap()
        val xml = parts.getValue(part).decodeToString()
        val bodyTop = 56.7f
        val bodyLeft = 56.7f
        val bodyRight = if (paper == "a4") 538.6f else 555.3f
        val frameWidth = 180f

        fun render(horizontal: String, vertical: String) = Loader.loadPDF(support.generate(PdfFixture.zip(parts.also {
            it[part] = xml.replace(pageX, horizontal).replace(pageY, vertical).toByteArray()
        }), "standard")).use { pdf ->
            PDFRenderer(pdf).renderImageWithDPI(0, 96f)
        }

        val absoluteY = offset("V", "page", bodyTop + 30f)
        val paragraphY = offset("V", "paragraph", 30f)
        for ((relative, expectedX) in listOf(
            offset("H", "column", 30f) to bodyLeft + 30f,
            offset("H", "margin", 30f) to bodyLeft + 30f,
            alignment("margin", "right") to bodyRight - frameWidth,
            alignment("column", "center") to (bodyRight + bodyLeft - frameWidth) / 2f,
        )) {
            assertImagesMatch(render(offset("H", "page", expectedX), absoluteY), render(relative, paragraphY)) {
                pixelTolerance = 0.0
                allowedDifferentPixelsPercent = 0.0
                threshold = 1
            }
        }
    }

    @Test
    fun `later paragraph and character anchors still identify the unsupported coordinate`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/floating-drawings/control/a4/source.docx"
        support.rejectChanged(source, part, "positionV") {
            it.replace(pageY, offset("V", "paragraph", 30f))
                .replace("<w:body>", "<w:body><w:p><w:r><w:t>BEFORE</w:t></w:r></w:p>")
        }
        support.rejectChanged(source, part, "positionH") {
            it.replace(pageX, offset("H", "character", 30f))
        }
    }

    private fun offset(axis: String, reference: String, points: Float) =
        "<wp:position$axis relativeFrom=\"$reference\"><wp:posOffset>${kotlin.math.round(points * 12700).toInt()}</wp:posOffset></wp:position$axis>"

    private fun alignment(reference: String, value: String) =
        "<wp:positionH relativeFrom=\"$reference\"><wp:align>$value</wp:align></wp:positionH>"

    private companion object {
        const val part = "word/document.xml"
        const val pageX = """<wp:positionH relativeFrom="page"><wp:posOffset>1270000</wp:posOffset></wp:positionH>"""
        const val pageY = """<wp:positionV relativeFrom="page"><wp:posOffset>1651000</wp:posOffset></wp:positionV>"""
    }
}

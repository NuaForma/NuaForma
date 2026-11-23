package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeDefaultTextFramePaintTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `standalone text frames with no fill or outline paint like explicit noFill`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes("/floating-drawings/control/a4/source.docx")).toMutableMap()
        val key = "word/document.xml"
        val xml = parts.getValue(key).decodeToString()
        val solid = "<a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill>"
        val stroke = Regex("<a:ln(?: [^>]+)?>.*?</a:ln>")
        check(solid in xml)
        check(stroke.containsMatchIn(xml))

        fun render(content: String): java.awt.image.BufferedImage {
            parts[key] = content.toByteArray()
            return Loader.loadPDF(support.generate(PdfFixture.zip(parts), "standard")).use { document ->
                assertEquals(1, document.numberOfPages)
                PDFRenderer(document).renderImageWithDPI(0, 96f)
            }
        }

        val omitted = render(xml.replace(solid, "").replace(stroke, ""))
        val transparent = render(xml.replace(solid, "<a:noFill/>").replace(stroke, "<a:ln><a:noFill/></a:ln>"))
        assertImagesMatch(omitted, transparent) {
            pixelTolerance = 0.0
            allowedDifferentPixelsPercent = 0.0
            threshold = 1
        }

        val painted = render(xml)
        assertNotEquals(
            painted.getRGB(0, 0, painted.width, painted.height, null, 0, painted.width).contentHashCode(),
            omitted.getRGB(0, 0, omitted.width, omitted.height, null, 0, omitted.width).contentHashCode(),
        )
    }
}

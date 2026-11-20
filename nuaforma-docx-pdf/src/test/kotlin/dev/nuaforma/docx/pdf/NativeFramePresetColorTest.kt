package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeFramePresetColorTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `opaque black and white preset paint matches explicit RGB paint`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes(source)).toMutableMap()
        val xml = parts.getValue(part).decodeToString()
        val rgb = xml.replace("<a:srgbClr val=\"FF0000\"/>", "<a:srgbClr val=\"FFFFFF\"/>")
            .replace("<a:srgbClr val=\"008B90\"/>", "<a:srgbClr val=\"000000\"/>")
        val preset = rgb.replace("<a:srgbClr val=\"FFFFFF\"/>", "<a:prstClr val=\"white\"/>")
            .replace("<a:srgbClr val=\"000000\"/>", "<a:prstClr val=\"black\"/>")

        fun render(content: String): java.awt.image.BufferedImage {
            parts[part] = content.toByteArray()
            return Loader.loadPDF(support.generate(PdfFixture.zip(parts), "standard")).use { document ->
                assertEquals(1, document.numberOfPages)
                PDFRenderer(document).renderImageWithDPI(0, 96f)
            }
        }

        assertImagesMatch(render(rgb), render(preset)) {
            pixelTolerance = 0.0
            allowedDifferentPixelsPercent = 0.0
            threshold = 1
        }
    }

    @Test
    fun `unknown or transformed preset paint retains its source location`() {
        val support = NativeWordFeatureSupport(temporary)
        for (color in listOf("<a:prstClr val=\"unknown\"/>",
            "<a:prstClr val=\"black\"><a:alpha val=\"0\"/></a:prstClr>",
            "<a:prstClr w:val=\"black\"/>")) {
            support.rejectChanged(source, part, "prstClr") { it.replaceFirst("<a:srgbClr val=\"FF0000\"/>", color) }
        }
    }

    private companion object {
        const val source = "/floating-drawings/control/a4/source.docx"
        const val part = "word/document.xml"
    }
}

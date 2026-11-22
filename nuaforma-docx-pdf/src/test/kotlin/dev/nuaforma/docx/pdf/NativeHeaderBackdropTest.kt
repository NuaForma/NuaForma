package dev.nuaforma.docx.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeHeaderBackdropTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `a plain Word rectangle paints behind the frame and logo without changing the body`() {
        val support = NativeWordFeatureSupport(temporary)
        Loader.loadPDF(support.generate(support.bytes(source), "standard")).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            assertEquals(1, pdf.getPage(0).resources.xObjectNames.count())
            val raster = PDFRenderer(pdf).renderImageWithDPI(0, 72f)
            assertEquals(0x03A783, raster.getRGB(5, 5) and 0xFFFFFF)
            assertEquals(0x03A783, raster.getRGB(raster.width - 7, 70) and 0xFFFFFF)
            assertEquals(0xFFFFFF, raster.getRGB(5, 75) and 0xFFFFFF)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(listOf("HEADER", "FRAME", "F00_BODY", "F01_NEXT").all(text::contains))
        }
    }

    @Test
    fun `a saved nonzero visual effect is not consumed as a passive rectangle style`() {
        NativeWordFeatureSupport(temporary).rejectChanged(source, "word/header7.xml", "drawing") {
            it.replaceFirst(Regex("""(?<=effectRef idx=")0(?=")"""), "2")
        }
    }

    private companion object {
        const val source = "/header-backdrop/a4-rectangle-logo.docx"
    }
}

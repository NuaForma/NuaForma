package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PdfWatermarkTest {
    private fun document() = PdfDocument(listOf(
        PdfPageSequence(PdfPage(595.28f, 841.89f, PdfMargins(30f, 30f, 30f, 30f)),
            listOf(PdfParagraph(listOf(PdfTextRun("Body A4", PdfTextStyle("regular", 11f)))))),
        PdfPageSequence(PdfPage(792f, 612f, PdfMargins(30f, 30f, 30f, 30f)),
            listOf(PdfParagraph(listOf(PdfTextRun("Body Letter", PdfTextStyle("regular", 11f))))))
    ))
    private fun positions(pdf: PDDocument): List<List<String>> = (0 until pdf.numberOfPages).map { index ->
        val result = mutableListOf<String>()
        object : PDFTextStripper() {
            init { startPage = index + 1; endPage = index + 1 }
            override fun processTextPosition(position: TextPosition) {
                result += "${position.unicode}:${position.x}:${position.y}:${position.fontSize}:${position.dir}"
            }
        }.getText(pdf)
        result
    }

    @Test fun `background watermark preserves body geometry and decorates every mixed-size page`() {
        val fonts = FoundationLetter.fonts()
        val original = PdfEngine().render(document(), fonts)
        val text = "DRAFT-057"
        val candidate = PdfEngine().render(document(), fonts, PdfOutputOptions(), PdfRenderControl(),
            PdfWatermark(text, "regular", PdfColor(18, 127, 135), 0.3f, -45f))
        Loader.loadPDF(original).use { plain -> Loader.loadPDF(candidate).use { marked ->
            assertEquals(2, marked.numberOfPages)
            assertEquals(positions(plain), positions(marked).map { it.drop(text.length) })
            for (page in marked.pages) {
                val alpha = page.resources.extGStateNames.map { page.resources.getExtGState(it).nonStrokingAlphaConstant }
                assertEquals(listOf(0.3f), alpha)
                assertTrue(page.resources.fontNames.all { page.resources.getFont(it).isEmbedded })
                assertTrue(page.resources.fontNames.all { page.resources.getFont(it).cosObject.getDictionaryObject(COSName.TO_UNICODE) != null })
            }
        } }
    }

    @Test fun `opaque decoration adds no transparency state and one-b always rejects before validation`() {
        val called = AtomicBoolean()
        val engine = PdfEngine(PdfConformanceValidator { _, _ -> called.set(true) })
        val opaque = PdfWatermark("FINAL", "regular", opacity = 1f, rotation = 0f)
        Loader.loadPDF(engine.render(document(), FoundationLetter.fonts(), PdfOutputOptions(), PdfRenderControl(), opaque)).use { pdf ->
            assertTrue(pdf.pages.all { !it.resources.extGStateNames.iterator().hasNext() })
        }
        val error = assertThrows(PdfRenderException::class.java) {
            engine.render(document(), FoundationLetter.fonts(), PdfOutputOptions(PdfOutputProfile.PDFA_1B), PdfRenderControl(), opaque)
        }
        assertEquals(PdfFailureCode.UNSUPPORTED_OUTPUT, error.code)
        assertFalse(called.get())
    }

    @Test fun `shared engine keeps concurrent watermark text and graphics state request-local`() {
        val engine = PdfEngine()
        val fonts = FoundationLetter.fonts()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 12).map { index -> executor.submit<String> {
                val token = "TENANT${index.toString().padStart(2, '0')}"
                val bytes = engine.render(document(), fonts, PdfOutputOptions(), PdfRenderControl(),
                    PdfWatermark(token, "regular", opacity = (index + 1) / 16f, rotation = index * 10f))
                Loader.loadPDF(bytes).use { pdf ->
                    val content = RenderPdfVisualFixtures.extractedText(pdf)
                    assertTrue(content.contains(token))
                    (0 until 12).filter { it != index }.forEach { other -> assertFalse(content.contains("TENANT${other.toString().padStart(2, '0')}")) }
                    assertEquals((index + 1) / 16f, pdf.getPage(0).resources.extGStateNames.single().let { pdf.getPage(0).resources.getExtGState(it).nonStrokingAlphaConstant })
                }
                token
            } }
            assertEquals(12, jobs.map { it.get(60, TimeUnit.SECONDS) }.distinct().size)
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun `qualified watermark scripts retain explicit logical Unicode under rotation`() {
        for (text in listOf("Сведения και στοιχεία", "טיוטה 57")) {
            val bytes = PdfEngine().render(document(), FoundationLetter.fonts(), PdfOutputOptions(), PdfRenderControl(), PdfWatermark(text, "regular"))
            Loader.loadPDF(bytes).use { pdf ->
                for (page in pdf.pages) {
                    val logical = PDFStreamParser(page).parse().filterIsInstance<COSDictionary>().mapNotNull { it.getString(COSName.ACTUAL_TEXT) }
                    assertEquals(listOf(text), logical)
                }
                if (text.startsWith("Сведения")) assertTrue(RenderPdfVisualFixtures.extractedText(pdf).contains(text))
            }
        }
    }

    @Test fun `unsupported shaping and missing watermark fonts fail instead of painting wrong text`() {
        val engine = PdfEngine()
        val fonts = FoundationLetter.fonts()
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            engine.render(document(), fonts, PdfOutputOptions(), PdfRenderControl(), PdfWatermark("مرحبا", "regular"))
        }.code)
        assertEquals(PdfFailureCode.FONT_UNAVAILABLE, assertThrows(PdfRenderException::class.java) {
            engine.render(document(), fonts, PdfOutputOptions(), PdfRenderControl(), PdfWatermark("DRAFT", "missing"))
        }.code)
        assertThrows(IllegalArgumentException::class.java) { PdfWatermark("DRAFT", "regular", opacity = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { PdfWatermark("DRAFT", "regular", rotation = Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { PdfWatermark("DRAFT\nNEXT", "regular") }
    }
}

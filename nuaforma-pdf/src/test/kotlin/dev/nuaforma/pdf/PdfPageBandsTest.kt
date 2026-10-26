package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfPageBandsTest {
    private val style = PdfTextStyle("regular", 10f)
    private val page = PdfPage(240f, 180f, PdfMargins(30f, 20f, 30f, 20f))
    private fun p(text: String) = PdfParagraph(listOf(PdfTextRun(text, style)), 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f))
    private fun band(text: String) = PdfPageBand(8f, 20f, listOf(p(text)))
    private fun variants(id: String, image: Boolean = false): PdfFlow {
        fun pair(label: String): PdfPageBands {
            val runs = mutableListOf<PdfInline>(PdfTextRun("F-$label-$id ", style), PdfPageNumber(style))
            if (image && label == "FIRST") runs += PdfImageRun(PdfImage(FoundationLetter.resource("/fixtures/nuaforma-icon-transparent.png"), 12f, 12f))
            return PdfPageBands(band("H-$label-$id"), PdfPageBand(8f, 20f, listOf(PdfParagraph(runs, 0f, 0f, PdfParagraphFormat(exactLineHeight = 18f)))))
        }
        val odd = pair("ODD")
        return PdfFlow(odd.header, odd.footer, firstPageBands = pair("FIRST"), evenPageBands = pair("EVEN"))
    }
    private fun sequence(policy: PdfFlow) = PdfPageSequence(page, List(24) { p("Body ${it + 1}") }, policy)

    @Test fun `first page overrides even selection and restarts at each range while parity stays global`() {
        val policy = variants("A", true)
        Loader.loadPDF(PdfEngine().render(PdfDocument(List(3) { sequence(policy) }), FoundationLetter.fonts())).use { pdf ->
            assertEquals(9, pdf.numberOfPages)
            for ((i, label) in listOf("FIRST", "EVEN", "ODD", "FIRST", "ODD", "EVEN", "FIRST", "EVEN", "ODD").withIndex()) {
                val text = PDFTextStripper().apply { startPage = i + 1; endPage = i + 1 }.getText(pdf)
                assertTrue(text.contains("H-$label-A")); assertTrue(text.contains("F-$label-A ${i + 1}"))
                for (other in listOf("FIRST", "EVEN", "ODD") - label) assertFalse(text.contains("H-$other-A"))
                assertEquals(label == "FIRST", pdf.getPage(i).resources.xObjectNames.any { pdf.getPage(i).resources.getXObject(it) is PDImageXObject })
            }
        }
    }

    @Test fun `explicit empty pairs suppress bands while absent policies retain shared bands`() {
        val default = PdfFlow(band("SHARED HEADER"), band("SHARED FOOTER"))
        for ((policy, expected) in listOf(default to listOf(true,true,true), default.copy(firstPageBands = PdfPageBands(), evenPageBands = PdfPageBands()) to listOf(false,false,true))) {
            Loader.loadPDF(PdfEngine().render(PdfDocument(listOf(sequence(policy))), FoundationLetter.fonts())).use { pdf ->
                for (i in expected.indices) {
                    val text = PDFTextStripper().apply { startPage = i + 1; endPage = i + 1 }.getText(pdf)
                    assertEquals(expected[i], text.contains("SHARED HEADER")); assertEquals(expected[i], text.contains("SHARED FOOTER"))
                }
            }
        }
    }

    @Test fun `variant resources participate in global budgets and selected bands validate fonts`() {
        val large = band("x".repeat(50_001))
        assertThrows(IllegalArgumentException::class.java) {
            PdfDocument(page, listOf(p("Body")), PdfFlow(firstPageBands = PdfPageBands(large), evenPageBands = PdfPageBands(large)))
        }
        val image = PdfPageBand(8f, 20f, listOf(PdfImage(ByteArray(4_000_000), 12f, 12f)))
        val policy = PdfFlow(image, image, firstPageBands = PdfPageBands(image, image), evenPageBands = PdfPageBands(image))
        assertThrows(IllegalArgumentException::class.java) { PdfEngine().render(PdfDocument(page, listOf(p("Body")), policy), FoundationLetter.fonts()) }
        val missing = PdfPageBand(8f, 20f, listOf(PdfParagraph(listOf(PdfTextRun("Missing", PdfTextStyle("absent", 10f))), 0f, 0f, PdfParagraphFormat(exactLineHeight = 14f))))
        assertEquals(PdfFailureCode.FONT_UNAVAILABLE, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(listOf(sequence(PdfFlow(evenPageBands = PdfPageBands(footer = missing))))), FoundationLetter.fonts())
        }.code)
    }

    @Test fun `shared engine retains request specific first even odd stories under concurrency`() {
        val engine = PdfEngine(); val fonts = FoundationLetter.fonts(); val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..7).map { id -> pool.submit {
                Loader.loadPDF(engine.render(PdfDocument(List(2) { sequence(variants(id.toString())) }), fonts)).use { pdf ->
                    assertEquals(6, pdf.numberOfPages)
                    val text = PDFTextStripper().getText(pdf)
                    assertTrue(text.contains("H-FIRST-$id") && text.contains("H-EVEN-$id") && text.contains("H-ODD-$id"))
                    for (other in 0..7) if (other != id) assertFalse(text.contains("H-FIRST-$other"))
                }
            } }
            jobs.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }
}

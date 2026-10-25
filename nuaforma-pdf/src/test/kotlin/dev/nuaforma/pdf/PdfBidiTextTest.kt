package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfBidiTextTest {
    private val style = PdfTextStyle("regular", 11f, languageTag = "he-IL")
    private fun paragraph(text: String, direction: PdfTextDirection? = PdfTextDirection.RIGHT_TO_LEFT) =
        PdfParagraph(listOf(PdfTextRun(text, style)), 0f, 0f,
            PdfParagraphFormat(alignment = PdfAlignment.RIGHT, exactLineHeight = 18f, textDirection = direction))
    private fun document(p: PdfParagraph, width: Float = 400f) =
        PdfDocument(PdfPage(width, 240f, PdfMargins(20f, 20f, 20f, 20f)), listOf(p), PdfFlow())
    private fun extract(p: PdfParagraph, sorted: Boolean = false, width: Float = 400f) =
        Loader.loadPDF(PdfEngine().render(document(p, width), FoundationLetter.fonts())).use { pdf ->
            PDFTextStripper().also { it.sortByPosition = sorted }.getText(pdf).trim().replace(Regex("\\s+"), " ")
        }

    @Test fun `Hebrew with numbers keeps logical copy order in both extraction modes`() {
        for (text in listOf("שלום עולם 123", "מחיר 123.45 עבור 67 יחידות")) {
            val direction = PdfTextDirection.RIGHT_TO_LEFT
            assertEquals(text, extract(paragraph(text, direction)))
            assertEquals(text, extract(paragraph(text, direction), true))
        }
    }

    @Test fun `paragraph bidi context survives wrapping and mixed face boundaries`() {
        val text = "שלום עולם מסמך בעברית עם טקסט נוסף לצורך בדיקת גלישת שורות והמשך העבודה"
        val p = paragraph(text)
        assertEquals(text, extract(p, width = 180f))
        val mixed = PdfParagraph(listOf(PdfTextRun("שלום ", style), PdfTextRun("עולם ", style.copy(fontId = "bold")), PdfTextRun("123", style)),
            0f, 0f, p.format)
        assertEquals("שלום עולם 123", extract(mixed))
    }

    @Test fun `unqualified direction controls marks mirroring and RTL indent fail explicitly`() {
        for (text in listOf("שָׁלוֹם", "مرحبا", "שלום (עולם)", "שלום\u200F", "\uFB1D", "שלום NuaForma עולם", "HEA שלום", "שלום Мир", "שלום Αθήνα")) {
            assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
                PdfEngine().render(document(paragraph(text)), FoundationLetter.fonts())
            }.code, text)
        }
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(document(paragraph("שלום", null)), FoundationLetter.fonts())
        }.code)
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(document(paragraph("שלום", PdfTextDirection.LEFT_TO_RIGHT)), FoundationLetter.fonts())
        }.code)
        val p = paragraph("שלום")
        assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(document(PdfParagraph(p.runs, format = p.format.copy(firstLineIndent = 10f))), FoundationLetter.fonts())
        }
        assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(document(PdfParagraph(p.runs + PdfTab(style), format = p.format)), FoundationLetter.fonts())
        }
        assertThrows(PdfRenderException::class.java) {
            val image = PdfImage(FoundationLetter.resource("/fixtures/nuaforma-icon-transparent.png"), 12f, 12f)
            PdfEngine().render(document(PdfParagraph(p.runs + PdfImageRun(image), format = p.format)), FoundationLetter.fonts())
        }
        assertThrows(PdfRenderException::class.java) {
            val label = PdfListLabel(PdfListValue("rtl", 0, 1, "1."), style)
            PdfEngine().render(document(PdfParagraph(p.runs, format = p.format, listLabel = label)), FoundationLetter.fonts())
        }
    }

    @Test fun `shared engine isolates Hebrew numbers under concurrency`() {
        val pool = Executors.newFixedThreadPool(4)
        val engine = PdfEngine(); val fonts = FoundationLetter.fonts()
        try {
            val jobs = (1..12).map { n -> pool.submit<String> {
                val text = "שלום עולם $n"
                Loader.loadPDF(engine.render(document(paragraph(text)), fonts)).use {
                    PDFTextStripper().getText(it).trim()
                }
            } }
            jobs.forEachIndexed { i, job -> assertEquals("שלום עולם ${i + 1}", job.get(30, TimeUnit.SECONDS)) }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }
}

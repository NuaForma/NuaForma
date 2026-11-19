package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfScalingBreakTest {
    private val plain = PdfTextStyle("regular", 12f)
    private val page = PdfPage(300f, 300f, PdfMargins(30f, 30f, 30f, 30f))

    private fun paragraph(runs: List<PdfInline>) = PdfParagraph(runs, 0f, 0f,
        PdfParagraphFormat(exactLineHeight = 20f, widowControl = false))

    @Test
    fun `horizontal scale changes every glyph advance and following text without leaking across cached shapes`() {
        for (features in listOf(PdfFontFeatures(), PdfFontFeatures(PdfKerning.PAIRS), PdfFontFeatures(PdfKerning.OPENTYPE))) {
            fun positions(scale: Float, selected: PdfFontFeatures = features): List<Float> {
                val positions = mutableListOf<Float>()
                val style = plain.withFontFeatures(selected).withCharacterSpacing(.5f)
                val model = PdfDocument(page, listOf(paragraph(listOf(PdfTextRun("AVATAR", style.withHorizontalScale(scale)), PdfTextRun("END", plain)))))
                Loader.loadPDF(PdfEngine().render(model, FoundationLetter.fonts())).use { pdf ->
                    object : PDFTextStripper() {

                        override fun processTextPosition(text: TextPosition) {
                            positions += text.xDirAdj
                            super.processTextPosition(text)
                        }

                    }.getText(pdf)
                }
                return positions
            }

            val normal = positions(1f)
            val unkerned = positions(1f, PdfFontFeatures())
            for (factor in listOf(.5f, .8f, 1.25f, 2f)) {
                val changed = positions(factor)
                for (i in 0..6) assertEquals(normal[i] + (unkerned[i] - 30f - i * .5f) * (factor - 1f), changed[i], .003f)
                assertEquals(normal[8] - normal[6], changed[8] - changed[6], .003f)
            }
        }
    }

    @Test
    fun `scale remains immutable through copies and decorations and rejects invalid factors`() {
        val scaled = plain.withHorizontalScale(.8f)
        for (copy in listOf(scaled.copy(), scaled.withBackground(PdfColor(1, 2, 3)), scaled.withStrikethrough(true),
            scaled.withFontFeatures(PdfFontFeatures(PdfKerning.PAIRS)), scaled.withSyntheticBold(true), scaled.withCharacterSpacing(1f)))
            assertEquals(.8f, copy.horizontalScale)
        assertEquals(plain, scaled.withHorizontalScale(1f))
        assertEquals(plain.hashCode(), scaled.withHorizontalScale(1f).hashCode())
        for (bad in listOf(0f, -.5f, .001f, 6.001f, Float.NaN, Float.POSITIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java) { plain.withHorizontalScale(bad) }
    }

    @Test
    fun `explicit breaks retain logical paragraphs and deliberate blank pages within the page budget`() {
        val runs = listOf(PdfTextRun("BEFORE", plain), PdfPageBreak(plain), PdfPageBreak(plain), PdfTextRun("AFTER", plain))
        val model = PdfDocument(page, listOf(paragraph(runs)), PdfFlow())
        Loader.loadPDF(PdfEngine().render(model, FoundationLetter.fonts())).use { pdf ->
            assertEquals(3, pdf.numberOfPages)
            for ((index, expected) in listOf("BEFORE", "", "AFTER").withIndex()) {
                val text = PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }.getText(pdf).trim()
                assertEquals(expected, text)
            }
        }
        assertEquals(1, model.blocks.size)
        val tooMany = PdfDocument(page, listOf(paragraph(List(33) { PdfPageBreak(plain) })), PdfFlow())
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, assertThrows(PdfRenderException::class.java) { PdfEngine().render(tooMany, FoundationLetter.fonts()) }.code)
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(page, listOf(paragraph(runs))), FoundationLetter.fonts())
        }.code)
    }

    @Test
    fun `body page breaks retain owning footnotes on their physical page`() {
        val note = PdfNote("note", PdfNoteKind.FOOTNOTE, PdfListValue("notes", 0, 1, "1"),
            listOf(paragraph(listOf(PdfNoteLabel(plain), PdfTextRun(" NOTE", plain)))))
        val body = paragraph(listOf(PdfTextRun("BEFORE", plain), PdfNoteCall("call", "note", plain),
            PdfPageBreak(plain), PdfTextRun("AFTER", plain)))
        val document = PdfDocument(page, listOf(body), PdfFlow(), notes = listOf(note))
        Loader.loadPDF(PdfEngine().render(document, FoundationLetter.fonts())).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            val first = PDFTextStripper().apply { endPage = 1 }.getText(pdf)
            val second = PDFTextStripper().apply { startPage = 2 }.getText(pdf)
            assertTrue(first.contains("BEFORE") && first.contains("NOTE"))
            assertTrue(second.contains("AFTER") && !second.contains("NOTE"))
        }
    }

    @Test
    fun `unsupported page-break containers reject rather than silently losing the boundary`() {
        val p = paragraph(listOf(PdfTextRun("BEFORE", plain), PdfPageBreak(plain), PdfTextRun("AFTER", plain)))
        val table = PdfTable(listOf(200f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(p))))))
        val frame = PdfTextBox(0f, 0f, 200f, 100f, listOf(p))
        for (block in listOf(table, frame)) {
            val error = assertThrows(PdfRenderException::class.java) {
                PdfEngine().render(PdfDocument(page, listOf(block), PdfFlow()), FoundationLetter.fonts())
            }
            assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, error.code)
            assertTrue(error.message!!.startsWith("Inline page breaks inside"))
        }
        val kept = p.copy(format = p.format.copy(keepLines = true))
        val error = assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(page, listOf(kept), PdfFlow()), FoundationLetter.fonts())
        }
        assertEquals("Inline page breaks with paragraph keep rules are not qualified", error.message)
    }

    @Test
    fun `one engine keeps concurrent scale and physical page boundaries request private`() {
        val engine = PdfEngine()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val jobs = (0..5).map { index -> workers.submit<Boolean> {
                val count = index % 3
                val style = plain.withHorizontalScale(if (index % 2 == 0) .8f else 1.25f)
                val runs = listOf(PdfTextRun("Request$index", style)) + List(count) { PdfPageBreak(style) } + PdfTextRun("END", style)
                Loader.loadPDF(engine.render(PdfDocument(page, listOf(paragraph(runs)), PdfFlow()), FoundationLetter.fonts())).use { pdf ->
                    assertEquals(count + 1, pdf.numberOfPages)
                    assertTrue(PDFTextStripper().getText(pdf).contains("Request$index"))
                }
                true
            } }
            jobs.forEach { assertTrue(it.get(30, TimeUnit.SECONDS)) }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

}

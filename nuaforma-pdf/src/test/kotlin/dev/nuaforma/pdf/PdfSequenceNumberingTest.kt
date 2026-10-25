package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfSequenceNumberingTest {
    private val style = PdfTextStyle("regular", 10f)
    private val page = PdfPage(240f, 180f, PdfMargins(30f,20f,30f,20f))
    private fun p(runs: List<PdfInline>) = PdfParagraph(runs,0f,0f,PdfParagraphFormat(exactLineHeight=14f))
    private fun body(count: Int) = List(count) { p(listOf(PdfTextRun("Body ${it+1}",style))) }
    private fun band(label: String) = PdfPageBand(8f,20f,listOf(p(listOf(PdfTextRun("$label P ",style),PdfPageNumber(style),PdfTextRun(" S ",style),PdfSequencePageCount(style),PdfTextRun(" T ",style),PdfPageCount(style)))))
    private fun policy(label: String,first: Boolean=false) = PdfFlow(band("$label ODD"),firstPageBands=if(first) PdfPageBands(band("$label FIRST")) else null,evenPageBands=PdfPageBands(band("$label EVEN")))
    private fun text(pdf: org.apache.pdfbox.pdmodel.PDDocument,n: Int) = PDFTextStripper().apply { startPage=n;endPage=n }.getText(pdf)

    @Test fun `explicit restart parity inserts a blank with new geometry and excludes it from sequence totals`() {
        for (align in listOf(true,false)) {
            val document=PdfDocument(listOf(
                PdfPageSequence(page,body(24),policy("A",true),PdfPageNumbering(25)),
                PdfPageSequence(page.copy(width=300f),body(24),policy("B"),PdfPageNumbering(1,align)),
                PdfPageSequence(page.copy(width=260f),body(24),policy("C")),
            ))
            Loader.loadPDF(PdfEngine().render(document,FoundationLetter.fonts())).use { pdf ->
                val total=if(align) 10 else 9;assertEquals(total,pdf.numberOfPages)
                assertTrue(text(pdf,1).contains("A FIRST P 25 S 3 T $total"))
                assertTrue(text(pdf,2).contains("A EVEN P 26 S 3 T $total"))
                if(align) {
                    assertTrue(text(pdf,4).isBlank());assertEquals(300f,pdf.getPage(3).mediaBox.width)
                    assertFalse(pdf.getPage(3).resources.fontNames.any());assertFalse(pdf.getPage(3).resources.xObjectNames.any())
                }
                val b=if(align) 5 else 4
                assertTrue(text(pdf,b).contains("B ODD P 1 S 3 T $total"))
                assertTrue(text(pdf,b+1).contains("B EVEN P 2 S 3 T $total"))
                assertTrue(text(pdf,b+3).contains("C EVEN P 4 S 3 T $total"))
                assertTrue(text(pdf,total).contains("C EVEN P 6 S 3 T $total"))
            }
        }
    }

    @Test fun `zero and four digit starts no leading padding single page ranges and page budgets are bounded`() {
        val engine=PdfEngine();val fonts=FoundationLetter.fonts()
        fun sequence(start: Int?,align: Boolean=false,flow: Boolean=true) = PdfPageSequence(page,body(1),if(flow) policy("N") else null,PdfPageNumbering(start,align))
        Loader.loadPDF(engine.render(PdfDocument(listOf(sequence(0,true),sequence(null))),fonts)).use { pdf ->
            assertEquals(2,pdf.numberOfPages);assertTrue(text(pdf,1).contains("N EVEN P 0 S 1 T 2"));assertTrue(text(pdf,2).contains("N ODD P 1 S 1 T 2"))
        }
        Loader.loadPDF(engine.render(PdfDocument(listOf(sequence(5,flow=false),sequence(null))),fonts)).use { pdf -> assertTrue(text(pdf,2).contains("N EVEN P 6 S 1 T 2")) }
        Loader.loadPDF(engine.render(PdfDocument(listOf(sequence(9_999))),fonts)).use { pdf -> assertTrue(text(pdf,1).contains("P 9999 S 1 T 1")) }
        assertEquals(PdfFailureCode.RESOURCE_LIMIT,assertThrows(PdfRenderException::class.java) { engine.render(PdfDocument(listOf(sequence(9_999),sequence(null))),fonts) }.code)
        assertEquals(PdfFailureCode.RESOURCE_LIMIT,assertThrows(PdfRenderException::class.java) { engine.render(PdfDocument(listOf(sequence(1),sequence(1,true)),2),fonts) }.code)
        Loader.loadPDF(engine.render(PdfDocument(listOf(sequence(9_999),sequence(0,true))),fonts)).use { pdf -> assertEquals(2,pdf.numberOfPages);assertTrue(text(pdf,2).contains("P 0 S 1 T 2")) }
        assertThrows(IllegalArgumentException::class.java) { PdfPageNumbering(-1) }
        assertThrows(IllegalArgumentException::class.java) { PdfPageNumbering(10_000) }
        assertThrows(IllegalArgumentException::class.java) { PdfPageNumbering(alignParity=true) }
    }

    @Test fun `sequence fields reject invalid contexts fonts and aggregate field expansion`() {
        val engine=PdfEngine();val fonts=FoundationLetter.fonts();val field=p(listOf(PdfSequencePageCount(style)))
        for(flow in listOf(null,PdfFlow())) assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,assertThrows(PdfRenderException::class.java) { engine.render(PdfDocument(page,listOf(field),flow),fonts) }.code)
        val table=PdfTable(listOf(200f),listOf(PdfTableRow(listOf(PdfTableCell(listOf(field))))))
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,assertThrows(PdfRenderException::class.java) { engine.render(PdfDocument(page,listOf(table),PdfFlow()),fonts) }.code)
        val frame=PdfTextBox(20f,30f,180f,40f,listOf(field))
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,assertThrows(PdfRenderException::class.java) { engine.render(PdfDocument(page,listOf(frame),PdfFlow()),fonts) }.code)
        for((run,count) in listOf(PdfPageNumber(style) to 26,PdfSequencePageCount(style) to 51)) {
            val large=p(List(1_000) { run });assertThrows(IllegalArgumentException::class.java) { PdfDocument(page,List(count) { large },PdfFlow()) }
        }
        val missing=PdfPageBand(8f,20f,listOf(p(listOf(PdfSequencePageCount(style.copy(fontId="absent"))))))
        assertEquals(PdfFailureCode.FONT_UNAVAILABLE,assertThrows(PdfRenderException::class.java) { engine.render(PdfDocument(page,body(1),PdfFlow(header=missing)),fonts) }.code)
    }

    @Test fun `shared engine keeps restart continuation padding and section totals request local`() {
        val engine=PdfEngine();val fonts=FoundationLetter.fonts();val pool=Executors.newFixedThreadPool(4)
        try {
            val jobs=(0..7).map { id -> pool.submit {
                val first=100+id;val padding=(first+2)%2!=1;val total=if(padding) 5 else 4
                val document=PdfDocument(listOf(PdfPageSequence(page,body(16),policy("JOB-$id"),PdfPageNumbering(first)),PdfPageSequence(page,body(16),policy("JOB-$id"),PdfPageNumbering(1,true))))
                Loader.loadPDF(engine.render(document,fonts)).use { pdf ->
                    assertEquals(total,pdf.numberOfPages)
                    assertTrue(text(pdf,1).contains("JOB-$id ${if(first%2==0) "EVEN" else "ODD"} P $first S 2 T $total"))
                    assertTrue(text(pdf,total).contains("JOB-$id EVEN P 2 S 2 T $total"))
                }
            } }
            jobs.forEach { it.get(60,TimeUnit.SECONDS) }
        } finally { pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS)) }
    }
}

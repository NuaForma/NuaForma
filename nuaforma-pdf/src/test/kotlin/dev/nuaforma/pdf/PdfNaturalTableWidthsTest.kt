package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfNaturalTableWidthsTest {
    private fun p(text:String)=PdfParagraph(listOf(PdfTextRun(text,PdfTextStyle("regular",10f))),0f,0f,PdfParagraphFormat(exactLineHeight=14f))
    private fun table(length:Int=15,id:String="JOB",policy:PdfTableWidthPolicy=PdfTableWidthPolicy.FIT_CONTENTS):PdfTable {
        fun row(a:String,b:String,c:String)=PdfTableRow(listOf(a,b,c).map {PdfTableCell(listOf(p(it)))})
        return PdfTable(listOf(180f,180f,180f),listOf(row(id,"Q","T"),row("A".repeat(length),"E","F")),widthPolicy=policy)
    }
    private fun document(table:PdfTable,band:Boolean=false):PdfDocument {
        val page=PdfPage(400f,400f,PdfMargins(if(band) 100f else 20f,20f,20f,20f))
        return if(band) PdfDocument(page,listOf(p("Body")),PdfFlow(header=PdfPageBand(5f,70f,listOf(table))))
        else PdfDocument(page,listOf(table),PdfFlow())
    }
    private fun inspect(bytes:ByteArray,id:String="JOB"):Float=Loader.loadPDF(bytes).use {pdf->
        assertEquals(1,pdf.numberOfPages)
        val positions=mutableListOf<TextPosition>()
        val text=object:PDFTextStripper() {
            override fun processTextPosition(text:TextPosition) {positions+=text;super.processTextPosition(text)}
        }.getText(pdf)
        assertEquals(1,Regex("\\b$id\\b").findAll(text).count())
        positions.single {it.unicode=="Q"}.xDirAdj
    }

    @Test fun `actual content replaces oversized grid hints and the widest later cell controls earlier columns`() {
        val engine=PdfEngine();val fonts=FoundationLetter.fonts()
        val compact=inspect(engine.render(document(table(15)),fonts))
        val wide=inspect(engine.render(document(table(30)),fonts))
        assertTrue(wide-compact>90f)
        assertTrue(wide<260f)
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW,assertThrows(PdfRenderException::class.java) {engine.render(document(table(policy=PdfTableWidthPolicy.FIXED)),fonts)}.code)
    }

    @Test fun `page band copying retains natural width policy`() {
        val bytes=PdfEngine().render(document(table(),band=true),FoundationLetter.fonts())
        Loader.loadPDF(bytes).use {pdf->
            val text=PDFTextStripper().getText(pdf)
            assertTrue(text.contains("JOB"));assertTrue(text.contains("Body"));assertTrue(text.contains("A".repeat(15)))
        }
    }

    @Test fun `natural overflow is explicit and text is never silently squeezed or wrapped`() {
        val engine=PdfEngine();val fonts=FoundationLetter.fonts()
        for(length in listOf(100,3000)) assertEquals(PdfFailureCode.CONTENT_OVERFLOW,assertThrows(PdfRenderException::class.java) {engine.render(document(table(length)),fonts)}.code)
    }

    @Test fun `unsupported spans nesting indentation controls and empty columns reject`() {
        fun single(cell:PdfTableCell)=PdfTable(listOf(100f),listOf(PdfTableRow(listOf(cell))),widthPolicy=PdfTableWidthPolicy.FIT_CONTENTS)
        val indent=PdfParagraph(listOf(PdfTextRun("X",PdfTextStyle("regular",10f))),format=PdfParagraphFormat(leftIndent=2f,exactLineHeight=14f))
        val nested=PdfTable(listOf(180f),listOf(PdfTableRow(listOf(PdfTableCell(listOf(table()))))))
        val span=PdfTable(listOf(100f,100f),listOf(PdfTableRow(listOf(PdfTableCell(listOf(p("X")),columnSpan=2)))),widthPolicy=PdfTableWidthPolicy.FIT_CONTENTS)
        val engine=PdfEngine();val fonts=FoundationLetter.fonts()
        val cases=listOf(single(PdfTableCell(listOf(indent))),single(PdfTableCell(listOf(p(" X")))),
            single(PdfTableCell(listOf(p("X\tY")))),single(PdfTableCell(listOf(p(" ")))),span,nested)
        for(value in cases) {
            assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,assertThrows(PdfRenderException::class.java) {engine.render(document(value),fonts)}.code)
        }
    }

    @Test fun `shared renderer measures each requests natural widths independently`() {
        val engine=PdfEngine();val fonts=FoundationLetter.fonts();val pool=Executors.newFixedThreadPool(3)
        try {
            val jobs=(0..5).map {i->pool.submit<Float> {inspect(engine.render(document(table(15+i*2,"JOB$i")),fonts),"JOB$i")}}
            val starts=jobs.map {it.get(60,TimeUnit.SECONDS)}
            starts.zipWithNext().forEach {(a,b)->assertTrue(b-a>10f)}
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS))}
    }
}

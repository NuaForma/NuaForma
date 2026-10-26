package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfTableAlignmentTest {
    private val style=PdfTextStyle("regular",10f)
    private val page=PdfPage(480f,360f,PdfMargins(30f,20f,30f,20f))
    private fun p(value:String)=PdfParagraph(listOf(PdfTextRun(value,style)),0f,0f,PdfParagraphFormat(exactLineHeight=14f))
    private fun cell(value:String,alignment:PdfVerticalAlignment=PdfVerticalAlignment.TOP,span:Int=1)=PdfTableCell(listOf(p(value)),rowSpan=span,verticalAlignment=alignment)
    private fun positions(pdf:org.apache.pdfbox.pdmodel.PDDocument):List<TextPosition> {
        val result=mutableListOf<TextPosition>()
        object:PDFTextStripper() {override fun processTextPosition(text:TextPosition) {result+=text;super.processTextPosition(text)}}.getText(pdf)
        return result
    }
    private fun row(lines:Int)=PdfTableRow(listOf(cell("A"),cell("B",PdfVerticalAlignment.CENTER),cell("C",PdfVerticalAlignment.BOTTOM),PdfTableCell(List(lines) {p("D")})))
    private fun assertAlignment(pdf:org.apache.pdfbox.pdmodel.PDDocument,lines:Int) {
        val points=positions(pdf)
        fun y(value:String)=points.single {it.unicode==value}.yDirAdj
        val first=points.filter {it.unicode=="D"}.minOf {it.yDirAdj}
        val last=points.filter {it.unicode=="D"}.maxOf {it.yDirAdj}
        assertEquals(first,y("A"),0.001f)
        assertEquals((first+last)/2,y("B"),0.001f)
        assertEquals(last,y("C"),0.001f)
        assertEquals(lines,points.count {it.unicode=="D"})
    }

    @Test fun `natural row height aligns top center bottom within the table flow profile`() {
        val table=PdfTable(List(4) {100f},listOf(row(4)))
        val engine=PdfEngine();val fonts=FoundationLetter.fonts()
        Loader.loadPDF(engine.render(PdfDocument(page,listOf(table),PdfFlow()),fonts)).use {assertAlignment(it,4)}
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW,assertThrows(PdfRenderException::class.java) {engine.render(PdfDocument(page,listOf(table)),fonts)}.code)
    }

    @Test fun `vertical spans align over all spanned rows and horizontal spans retain the policy`() {
        val rows=(0..2).map {index->PdfTableRow((if(index==0) listOf(cell("B",PdfVerticalAlignment.CENTER,3),cell("C",PdfVerticalAlignment.BOTTOM,3)) else emptyList())+PdfTableCell(List(4) {p("D")}))}
        val table=PdfTable(List(3) {120f},rows)
        Loader.loadPDF(PdfEngine().render(PdfDocument(page,listOf(table),PdfFlow()),FoundationLetter.fonts())).use {pdf->
            val points=positions(pdf);val ds=points.filter {it.unicode=="D"};val first=ds.minOf {it.yDirAdj};val last=ds.maxOf {it.yDirAdj}
            assertEquals((first+last)/2,points.single {it.unicode=="B"}.yDirAdj,0.001f)
            assertEquals(last,points.single {it.unicode=="C"}.yDirAdj,0.001f)
            assertEquals(12,ds.size)
        }
        val span=PdfTableCell(listOf(p("B")),columnSpan=2,verticalAlignment=PdfVerticalAlignment.CENTER)
        val wide=PdfTable(List(3) {120f},listOf(PdfTableRow(listOf(span,PdfTableCell(List(4) {p("D")})))))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page,listOf(wide),PdfFlow()),FoundationLetter.fonts())).use {pdf->
            val points=positions(pdf);val ds=points.filter {it.unicode=="D"}
            assertEquals((ds.first().yDirAdj+ds.last().yDirAdj)/2,points.single {it.unicode=="B"}.yDirAdj,0.001f)
        }
    }

    @Test fun `band field resolution and nested tables preserve cell alignment`() {
        val table=PdfTable(List(4) {100f},listOf(row(4)))
        val bandPage=page.copy(margins=PdfMargins(100f,20f,30f,20f))
        Loader.loadPDF(PdfEngine().render(PdfDocument(bandPage,listOf(p("body")),PdfFlow(header=PdfPageBand(5f,80f,listOf(table)))),FoundationLetter.fonts())).use {assertAlignment(it,4)}
        val nested=PdfTable(List(2) {80f},listOf(PdfTableRow(listOf(cell("B",PdfVerticalAlignment.CENTER),PdfTableCell(List(4) {p("D")})))))
        val outer=PdfTable(listOf(100f,200f),listOf(PdfTableRow(listOf(cell("C",PdfVerticalAlignment.BOTTOM),PdfTableCell(listOf(nested))))))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page,listOf(outer),PdfFlow()),FoundationLetter.fonts())).use {pdf->
            val points=positions(pdf);val ds=points.filter {it.unicode=="D"}
            assertEquals((ds.first().yDirAdj+ds.last().yDirAdj)/2,points.single {it.unicode=="B"}.yDirAdj,0.001f)
            assertTrue(points.single {it.unicode=="C"}.yDirAdj>=ds.last().yDirAdj)
        }
    }

    @Test fun `fragmented rows reject unqualified vertical alignment before publishing bytes`() {
        fun document(alignment:PdfVerticalAlignment)=PdfDocument(page,listOf(PdfTable(listOf(100f,200f),listOf(PdfTableRow(listOf(cell("B",alignment),PdfTableCell(List(40) {p("D")})),allowSplit=true)))),PdfFlow())
        val engine=PdfEngine();val fonts=FoundationLetter.fonts()
        val failure=assertThrows(PdfRenderException::class.java) {engine.render(document(PdfVerticalAlignment.CENTER),fonts)}
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,failure.code)
        Loader.loadPDF(engine.render(document(PdfVerticalAlignment.TOP),fonts)).use {assertTrue(it.numberOfPages>1);assertEquals(40,positions(it).count {p->p.unicode=="D"})}
        val merged=(0..2).map {index->PdfTableRow((if(index==0) listOf(cell("B",PdfVerticalAlignment.CENTER,3)) else emptyList())+PdfTableCell(List(8) {p("D")}))}
        val spanning=PdfDocument(page,listOf(PdfTable(listOf(100f,200f),merged)),PdfFlow())
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,assertThrows(PdfRenderException::class.java) {engine.render(spanning,fonts)}.code)
    }

    @Test fun `shared engine keeps natural row heights and cell alignment request local`() {
        val engine=PdfEngine();val fonts=FoundationLetter.fonts();val pool=Executors.newFixedThreadPool(4)
        try {
            val jobs=(3..10).map {lines->pool.submit {
                val table=PdfTable(List(4) {100f},listOf(row(lines)))
                Loader.loadPDF(engine.render(PdfDocument(page,listOf(table),PdfFlow()),fonts)).use {assertAlignment(it,lines)}
            }}
            jobs.forEach {it.get(60,TimeUnit.SECONDS)}
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS))}
    }
}

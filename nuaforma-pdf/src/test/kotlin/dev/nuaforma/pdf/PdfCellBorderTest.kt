package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfCellBorderTest {
    private val red=PdfColor(170,20,40)
    private val blue=PdfColor(0,80,140)
    private val style=PdfTextStyle("regular",10f)
    private val page=PdfPage(400f,300f,PdfMargins(30f,20f,30f,20f))
    private fun p(text:String)=PdfParagraph(listOf(PdfTextRun(text,style)),0f,0f,PdfParagraphFormat(exactLineHeight=14f))
    private fun all(stroke:PdfEdgeStroke?)=PdfCellBorders(stroke,stroke,stroke,stroke)
    private fun rows(count:Int,borders:PdfCellBorders?)=List(count) {i->PdfTableRow(listOf(PdfTableCell(listOf(p("R$i")),borders=borders)))}
    private fun tokens(pdf:org.apache.pdfbox.pdmodel.PDDocument)=PDFStreamParser(pdf.getPage(0)).parse()
    private fun colors(tokens:List<Any>):List<List<Float>> = tokens.mapIndexedNotNull {index,value->
        if(value is Operator && value.name=="rg") (index-3 until index).map {(tokens[it] as COSNumber).floatValue()} else null
    }
    private fun containsColor(colors:List<List<Float>>,color:PdfColor)=colors.any {values->
        values.zip(listOf(color.red,color.green,color.blue)).all {(value,channel)->kotlin.math.abs(value-channel/255f)<0.00001f}
    }

    @Test fun `shared edges collapse by priority width and stable order within their table only`() {
        val group=Any()
        fun edge(start:Float,length:Float,color:PdfColor,width:Float,priority:Long=0,owner:Any=group)=PositionedTableBorder(start,20f,length,true,PdfEdgeStroke(PdfBorder(width,color),priority),owner)
        val result=TableBorderLayouter.rectangles(listOf(edge(10f,100f,red,1f,1),edge(60f,100f,blue,2f,2)),200f,100f)
        assertEquals(2,result.size);assertEquals(red,result[0].fill);assertEquals(50f,result[0].width)
        assertEquals(blue,result[1].fill);assertEquals(60f,result[1].x);assertEquals(100f,result[1].width)
        val wider=TableBorderLayouter.rectangles(listOf(edge(10f,100f,red,1f),edge(10f,100f,blue,2f)),200f,100f)
        assertEquals(blue,wider.single().fill)
        val priority=TableBorderLayouter.rectangles(listOf(edge(10f,100f,red,4f,1),edge(10f,100f,blue,1f,2)),200f,100f)
        assertEquals(blue,priority.single().fill)
        val first=TableBorderLayouter.rectangles(listOf(edge(10f,100f,red,1f),edge(10f,100f,blue,1f)),200f,100f)
        assertEquals(red,first.single().fill)
        val separate=TableBorderLayouter.rectangles(listOf(edge(10f,100f,red,1f),edge(10f,100f,blue,2f,owner=Any())),200f,100f)
        assertEquals(2,separate.size)
    }

    @Test fun `edge geometry is bounded and oversized edge inventories fail explicitly`() {
        val stroke=PdfEdgeStroke(PdfBorder(4f,red));val group=Any()
        val normal=PositionedTableBorder(20f,20f,30f,true,stroke,group)
        for(bad in listOf(normal.copy(length=Float.NaN),normal.copy(length=0f),normal.copy(x=-10f),normal.copy(horizontal=false,x=0f))) {
            assertEquals(PdfFailureCode.CONTENT_OVERFLOW,assertThrows(PdfRenderException::class.java) {TableBorderLayouter.rectangles(listOf(bad),100f,100f)}.code)
        }
        assertEquals(PdfFailureCode.RESOURCE_LIMIT,assertThrows(PdfRenderException::class.java) {TableBorderLayouter.rectangles(List(20_001) {normal},100f,100f)}.code)
        assertThrows(IllegalArgumentException::class.java) {PdfEdgeStroke(PdfBorder(1f,red),-1)}
        val headerTable=PdfTable(listOf(180f),rows(300,all(PdfEdgeStroke(PdfBorder(1f,red)))))
        val large=PdfPage(1000f,10000f,PdfMargins(7200f,20f,20f,20f))
        val body=List(32) {PdfParagraph(listOf(PdfTextRun("Body",style)),0f,0f,PdfParagraphFormat(exactLineHeight=14f,pageBreakBefore=true))}
        val document=PdfDocument(large,body,PdfFlow(header=PdfPageBand(5f,7000f,listOf(headerTable))))
        assertEquals(PdfFailureCode.RESOURCE_LIMIT,assertThrows(PdfRenderException::class.java) {PdfEngine().render(document,FoundationLetter.fonts())}.code)
    }

    @Test fun `horizontal bands cover vertical junctions independently of collinear priority`() {
        val group=Any()
        val horizontal=PositionedTableBorder(10f,20f,100f,true,PdfEdgeStroke(PdfBorder(1f,red),1),group)
        val vertical=PositionedTableBorder(60f,10f,50f,false,PdfEdgeStroke(PdfBorder(4f,blue),100),group)
        val result=TableBorderLayouter.rectangles(listOf(horizontal,vertical),140f,100f)
        assertEquals(red,result.last {60f>=it.x && 60f<it.x+it.width && 20.5f>=it.top && 20.5f<it.top+it.height}.fill)
        val ending=PositionedTableBorder(110f,10f,10f,false,PdfEdgeStroke(PdfBorder(1f,blue)),group)
        assertEquals(100.5f,TableBorderLayouter.rectangles(listOf(horizontal,ending),140f,100f).last().width)
    }

    @Test fun `raw row top insets and final bottom reserve determine actual PDF text positions`() {
        val strong=PdfEdgeStroke(PdfBorder(4f,red))
        val thin=PdfEdgeStroke(PdfBorder(2f,blue))
        val table=PdfTable(listOf(180f),listOf(
            PdfTableRow(listOf(PdfTableCell(listOf(p("A")),borders=PdfCellBorders(top=strong)))),
            PdfTableRow(listOf(PdfTableCell(listOf(p("B")),borders=PdfCellBorders(top=thin,bottom=strong)))),
        ))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page,listOf(table,p("C")),PdfFlow()),FoundationLetter.fonts())).use {pdf->
            val positions=mutableListOf<TextPosition>()
            object:PDFTextStripper() {override fun processTextPosition(text:TextPosition) {positions+=text;super.processTextPosition(text)}}.getText(pdf)
            fun y(s:String)=positions.single {it.unicode==s}.yDirAdj
            assertEquals(24f,y("B")-y("A"),0.001f)
            assertEquals(22f,y("C")-y("B"),0.001f)
            val stream=tokens(pdf)
            assertTrue(containsColor(colors(stream),red));assertTrue(containsColor(colors(stream),blue))
            assertTrue(stream.filterIsInstance<Operator>().none {it.name=="S"})
        }
        val aligned=PdfTable(List(3) {100f},listOf(PdfTableRow(listOf(
            PdfTableCell(listOf(p("A")),verticalAlignment=PdfVerticalAlignment.CENTER,borders=all(thin)),
            PdfTableCell(listOf(p("B")),verticalAlignment=PdfVerticalAlignment.BOTTOM,borders=all(strong)),
            PdfTableCell(List(4) {p("D")},borders=PdfCellBorders()),
        ))))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page,listOf(aligned),PdfFlow()),FoundationLetter.fonts())).use {pdf->
            val positions=mutableListOf<TextPosition>()
            object:PDFTextStripper() {override fun processTextPosition(text:TextPosition) {positions+=text;super.processTextPosition(text)}}.getText(pdf)
            val first=positions.first {it.unicode=="D"}.yDirAdj
            assertEquals(21f,positions.single {it.unicode=="A"}.yDirAdj-first,0.001f)
            assertEquals(42f,positions.single {it.unicode=="B"}.yDirAdj-first,0.001f)
        }
    }

    @Test fun `empty explicit policies remove strokes while null policies retain uniform borders`() {
        val engine=PdfEngine();val fonts=FoundationLetter.fonts()
        for(policy in listOf(null,PdfCellBorders())) {
            val table=PdfTable(listOf(180f),rows(1,policy))
            Loader.loadPDF(engine.render(PdfDocument(page,listOf(table),PdfFlow()),fonts)).use {pdf->
                assertTrue(PDFTextStripper().getText(pdf).contains("R0"))
                assertEquals(policy==null,tokens(pdf).filterIsInstance<Operator>().any {it.name=="S"})
            }
        }
    }

    @Test fun `unqualified merged fragmented and multiple page custom tables reject without changing uniform pagination`() {
        val policy=all(PdfEdgeStroke(PdfBorder(1f,red)));val engine=PdfEngine();val fonts=FoundationLetter.fonts()
        val merged=PdfTable(listOf(90f,90f),listOf(PdfTableRow(listOf(PdfTableCell(listOf(p("A")),columnSpan=2,borders=policy)))))
        val long=PdfTable(listOf(180f),listOf(PdfTableRow(listOf(PdfTableCell(List(50) {p("A")},borders=policy)),allowSplit=true)))
        val nested=PdfTable(listOf(180f),listOf(PdfTableRow(listOf(PdfTableCell(listOf(PdfTable(listOf(150f),rows(1,null))),borders=policy)))))
        for(table in listOf(merged,long,nested,PdfTable(listOf(180f),rows(20,policy)))) {
            assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,assertThrows(PdfRenderException::class.java) {engine.render(PdfDocument(page,listOf(table),PdfFlow()),fonts)}.code)
        }
        Loader.loadPDF(engine.render(PdfDocument(page,listOf(PdfTable(listOf(180f),rows(20,null))),PdfFlow()),fonts)).use {assertTrue(it.numberOfPages>1)}
    }

    @Test fun `page band field resolution preserves all cell edges and priorities`() {
        val policy=all(PdfEdgeStroke(PdfBorder(1f,blue),77))
        val field=PdfParagraph(listOf(PdfPageNumber(style)),0f,0f,PdfParagraphFormat(exactLineHeight=14f))
        val table=PdfTable(listOf(180f),listOf(PdfTableRow(listOf(PdfTableCell(listOf(field),borders=policy)))))
        val bandPage=page.copy(margins=PdfMargins(100f,20f,30f,20f))
        Loader.loadPDF(PdfEngine().render(PdfDocument(bandPage,listOf(p("body")),PdfFlow(header=PdfPageBand(5f,80f,listOf(table)))),FoundationLetter.fonts())).use {pdf->
            assertTrue(PDFTextStripper().getText(pdf).contains("1"));assertTrue(containsColor(colors(tokens(pdf)),blue))
        }
    }

    @Test fun `shared engine keeps border groups colors and precedence request local`() {
        val engine=PdfEngine();val fonts=FoundationLetter.fonts();val pool=Executors.newFixedThreadPool(4)
        try {
            val jobs=(0..7).map {id->pool.submit {
                val color=PdfColor(id*20,40,180)
                val policy=all(PdfEdgeStroke(PdfBorder(1f,color),id.toLong()))
                val table=PdfTable(listOf(180f),rows(id+1,policy))
                Loader.loadPDF(engine.render(PdfDocument(page,listOf(table),PdfFlow()),fonts)).use {pdf->
                    assertEquals(id+1,Regex("R[0-9]+").findAll(PDFTextStripper().getText(pdf)).count())
                    val values=colors(tokens(pdf));assertTrue(containsColor(values,color));assertFalse(containsColor(values,red))
                }
            }}
            jobs.forEach {it.get(60,TimeUnit.SECONDS)}
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS))}
    }
}

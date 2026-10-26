package dev.nuaforma.pdf

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

class PdfBorderCombinationsTest {
    private val blue=PdfColor(0,81,140)
    private val teal=PdfColor(0,153,159)
    private val style=PdfTextStyle("regular",10f)
    private val margins=PdfMargins(4f,6f,4f,6f)
    private fun p(text:String)=PdfParagraph(listOf(PdfTextRun(text,style)),0f,0f,PdfParagraphFormat(exactLineHeight=14f))
    private fun stroke(width:Float,color:PdfColor=blue)=PdfEdgeStroke(PdfBorder(width,color))
    private fun policy(edge:PdfEdgeStroke)=PdfCellBorders(edge,edge,edge,edge)
    private fun glyphs(pdf:org.apache.pdfbox.pdmodel.PDDocument):List<TextPosition> {
        val positions=mutableListOf<TextPosition>()
        object:PDFTextStripper() {override fun processTextPosition(text:TextPosition) {positions+=text;super.processTextPosition(text)}}.getText(pdf)
        return positions
    }
    private fun merged():PdfDocument {
        val edge=stroke(2f);val all=policy(edge)
        val table=PdfTable(listOf(180f,180f),listOf(
            PdfTableRow(listOf(PdfTableCell(listOf(p("A")),columnSpan=2,borders=all))),
            PdfTableRow(listOf(PdfTableCell(listOf(p("B"),p("G")),rowSpan=2,borders=all),PdfTableCell(listOf(p("C")),borders=all))),
            PdfTableRow(listOf(PdfTableCell(listOf(p("D")),borders=all.copy(bottom=null))))
        ),margins)
        return PdfDocument(PdfPage(440f,600f,PdfMargins(30f,30f,30f,30f)),listOf(table,p("E")),PdfFlow())
    }
    private fun nested():PdfDocument {
        val inside=PdfTable(listOf(156f),listOf("N1","N2").map {PdfTableRow(listOf(PdfTableCell(listOf(p(it)),borders=policy(stroke(1f,teal)))))})
        val outside=PdfTable(listOf(180f,180f),listOf(PdfTableRow(listOf(
            PdfTableCell(listOf(p("Before"),inside,p("After")),verticalAlignment=PdfVerticalAlignment.BOTTOM,borders=policy(stroke(2f))),
            PdfTableCell(List(12) {p("Side")},borders=policy(stroke(2f)))
        ))))
        return PdfDocument(PdfPage(440f,600f,PdfMargins(30f,30f,30f,30f)),listOf(outside),PdfFlow())
    }
    private fun paginated(custom:Boolean=true):PdfDocument {
        val edge=stroke(2f)
        val rows=(listOf("Header")+(1..40).map {"Row%03d".format(it)}).map {PdfTableRow(listOf(PdfTableCell(listOf(p(it)),borders=if(custom) policy(edge) else null)))}
        val table=PdfTable(listOf(200f),rows,margins,if(custom) PdfBorder(.5f,teal) else edge.border,1)
        return PdfDocument(PdfPage(500f,320f,PdfMargins(20f,20f,20f,20f)),listOf(table),PdfFlow())
    }

    @Test fun `merged cells retain text avoid interior rules and reserve their final bottom stroke`() {
        Loader.loadPDF(PdfEngine().render(merged(),FoundationLetter.fonts())).use {pdf->
            assertEquals(1,pdf.numberOfPages)
            val positions=glyphs(pdf)
            fun y(value:String)=positions.single {it.unicode==value}.yDirAdj
            assertEquals(24f,y("B")-y("A"),.001f)
            assertEquals(24f,y("D")-y("C"),.001f)
            assertEquals(14f,y("G")-y("B"),.001f)
            assertEquals(20f,y("E")-y("D"),.001f)
            val tokens=PDFStreamParser(pdf.getPage(0)).parse()
            val rectangles=tokens.mapIndexedNotNull {index,value->
                if(value is Operator&&value.name=="re") (index-4 until index).map {(tokens[it] as COSNumber).floatValue()} else null
            }
            assertTrue(rectangles.none {(x,y,width,height)->height==2f&&kotlin.math.abs(600f-y-height-78f)<.001f&&x<=40f&&x+width>40f})
            assertEquals(listOf("A","B","G","C","D","E"),positions.map {it.unicode})
        }
    }

    @Test fun `nested tables keep their own border colors and bottom aligned parent contents`() {
        Loader.loadPDF(PdfEngine().render(nested(),FoundationLetter.fonts())).use {pdf->
            assertEquals(1,pdf.numberOfPages)
            val text=PDFTextStripper().getText(pdf)
            for(marker in listOf("Before","After","N1","N2")) assertEquals(1,Regex("\\b$marker\\b").findAll(text).count())
            val positions=glyphs(pdf);assertTrue(positions.all {it.xDirAdj>=30f&&it.xDirAdj+it.widthDirAdj<=410f&&it.yDirAdj in 30f..570f})
            assertTrue(positions.first {it.unicode=="B"}.yDirAdj>positions.first {it.unicode=="S"}.yDirAdj)
            val tokens=PDFStreamParser(pdf.getPage(0)).parse()
            val colors=tokens.mapIndexedNotNull {index,value->if(value is Operator&&value.name=="rg") (index-3 until index).map {(tokens[it] as COSNumber).floatValue()} else null}
            for(color in listOf(blue,teal)) assertTrue(colors.any {channels->channels.zip(listOf(color.red,color.green,color.blue)).all {(a,b)->kotlin.math.abs(a-b/255f)<.00001f}})
        }
    }

    @Test fun `custom border pagination matches uniform text geometry and repeats only table headers`() {
        val engine=PdfEngine();val fonts=FoundationLetter.fonts()
        Loader.loadPDF(engine.render(paginated(),fonts)).use {custom->Loader.loadPDF(engine.render(paginated(false),fonts)).use {uniform->
            assertEquals(4,custom.numberOfPages);assertEquals(uniform.numberOfPages,custom.numberOfPages)
            assertEquals(PDFTextStripper().getText(uniform),PDFTextStripper().getText(custom))
            val a=glyphs(uniform);val b=glyphs(custom);assertEquals(a.size,b.size)
            for((left,right) in a.zip(b)) {assertEquals(left.unicode,right.unicode);assertEquals(left.xDirAdj,right.xDirAdj,.001f);assertEquals(left.yDirAdj,right.yDirAdj,.001f)}
            val text=PDFTextStripper().getText(custom)
            assertEquals(4,Regex("Header").findAll(text).count())
            for(i in 1..40) assertEquals(1,Regex("Row%03d".format(i)).findAll(text).count())
        }}
    }

    @Test fun `shared engine isolates concurrent merged nested and paginated border requests`() {
        val documents=listOf(merged(),nested(),paginated());val engine=PdfEngine();val fonts=FoundationLetter.fonts();val pool=Executors.newFixedThreadPool(3)
        try {
            val jobs=(0..8).map {id->pool.submit {
                val source=documents[id%documents.size]
                val document=PdfDocument(source.page,listOf(p("JOB$id"))+source.blocks,source.flow)
                Loader.loadPDF(engine.render(document,fonts)).use {pdf->
                    val text=PDFTextStripper().getText(pdf)
                    assertEquals(listOf("JOB$id"),Regex("JOB[0-9]+").findAll(text).map {it.value}.toList())
                    assertTrue(pdf.numberOfPages in 1..5)
                }
            }}
            jobs.forEach {it.get(60,TimeUnit.SECONDS)}
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS))}
    }

    @Test fun `vertical spans close at page cuts and reopen without repeated source text`() {
        val all=policy(stroke(1f))
        val rows=(0 until 20).map {index->PdfTableRow(buildList {
            if(index==0) add(PdfTableCell(listOf(p("Span")),rowSpan=20,borders=all))
            add(PdfTableCell(listOf(p("R%02d".format(index))),borders=all))
        })}
        val document=PdfDocument(PdfPage(300f,230f,PdfMargins(20f,20f,20f,20f)),listOf(PdfTable(listOf(100f,100f),rows)),PdfFlow())
        Loader.loadPDF(PdfEngine().render(document,FoundationLetter.fonts())).use {pdf->
            assertEquals(3,pdf.numberOfPages)
            val text=PDFTextStripper().getText(pdf)
            assertEquals(1,Regex("Span").findAll(text).count())
            for(i in 0 until 20) assertEquals(1,Regex("R%02d".format(i)).findAll(text).count())
            fun fullRule(page:Int,top:Float):Boolean {
                val tokens=PDFStreamParser(pdf.getPage(page)).parse()
                return tokens.mapIndexedNotNull {index,value->if(value is Operator&&value.name=="re") (index-4 until index).map {(tokens[it] as COSNumber).floatValue()} else null}
                    .any {(x,y,width,height)->kotlin.math.abs(x-19.5f)<.001f&&kotlin.math.abs(width-201f)<.001f&&height==1f&&kotlin.math.abs(230f-y-height-top)<.001f}
            }
            assertTrue(fullRule(0,204f),"Missing closing rule through the continuing span")
            assertTrue(fullRule(1,20f),"Missing opening rule through the continuing span")
        }
    }
}

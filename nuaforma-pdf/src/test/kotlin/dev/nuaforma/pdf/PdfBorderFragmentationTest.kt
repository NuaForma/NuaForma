package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfBorderFragmentationTest {
    private val stroke=PdfBorder(2f,PdfColor(0,80,140))
    private fun paragraph(text:String)=PdfParagraph(listOf(PdfTextRun(text,PdfTextStyle("regular",10f))),0f,0f,PdfParagraphFormat(exactLineHeight=14f))
    private fun document(custom:Boolean,merged:Boolean,id:String="JOB",split:Boolean=true,alignment:PdfVerticalAlignment=PdfVerticalAlignment.TOP):PdfDocument {
        val edge=PdfEdgeStroke(stroke)
        val policy=if(custom) PdfCellBorders(edge,edge,edge,edge) else null
        fun cell(text:List<String>,span:Int=1,align:PdfVerticalAlignment=PdfVerticalAlignment.TOP)=PdfTableCell(text.map(::paragraph),columnSpan=span,verticalAlignment=align,borders=policy)
        val cells=mutableListOf(cell(listOf(id)),cell((1..46).map {"Line%03d".format(it)},if(merged) 2 else 1,alignment))
        if(!merged) cells+=cell(listOf("DONE"))
        val table=PdfTable(listOf(60f,180f,60f),listOf(
            PdfTableRow(listOf(cell(listOf("Code")),cell(listOf("Header")),cell(listOf("State")))),
            PdfTableRow(cells,allowSplit=split,splitOversized=!split)),border=if(custom) PdfBorder(.5f,PdfColor(184,197,204)) else stroke,repeatedHeaderRows=1)
        return PdfDocument(PdfPage(400f,320f,PdfMargins(20f,20f,20f,20f)),listOf(table),PdfFlow())
    }
    private fun inspect(bytes:ByteArray,id:String="JOB",requireBorders:Boolean=false):List<Triple<String,Float,Float>> = Loader.loadPDF(bytes).use {pdf->
        assertEquals(3,pdf.numberOfPages)
        if(requireBorders) for(page in pdf.pages) {
            val tokens=PDFStreamParser(page).parse()
            val rules=tokens.mapIndexedNotNull {index,value->
                if(value is Operator&&value.name=="re") (index-4 until index).map {(tokens[it] as COSNumber).floatValue()} else null
            }.filter {kotlin.math.abs(it[0]-19f)<.001f&&kotlin.math.abs(it[2]-302f)<.001f&&kotlin.math.abs(it[3]-2f)<.001f}
            assertEquals(3,rules.size,"Every page needs the header opening, fragment opening and fragment closing rules")
        }
        val positions=mutableListOf<Triple<String,Float,Float>>()
        val text=object:PDFTextStripper() {
            override fun processTextPosition(text:TextPosition) {positions+=Triple(text.unicode,text.xDirAdj,text.yDirAdj);super.processTextPosition(text)}
        }.getText(pdf)
        assertEquals(3,Regex("Header").findAll(text).count())
        assertEquals(1,Regex("\\b$id\\b").findAll(text).count())
        for(i in 1..46) assertEquals(1,Regex("Line%03d".format(i)).findAll(text).count())
        positions
    }

    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `cell borders retain uniform row pagination and every glyph position`(merged:Boolean) {
        val engine=PdfEngine();val fonts=FoundationLetter.fonts()
        val uniform=inspect(engine.render(document(false,merged),fonts))
        val custom=inspect(engine.render(document(true,merged),fonts),requireBorders=true)
        assertEquals(uniform.size,custom.size)
        uniform.zip(custom).forEach {(a,b)->assertEquals(a.first,b.first);assertEquals(a.second,b.second,.001f);assertEquals(a.third,b.third,.001f)}
    }

    @Test fun `unqualified alignment and oversized non splitting rows fail explicitly`() {
        val engine=PdfEngine();val fonts=FoundationLetter.fonts()
        for(doc in listOf(document(true,false,split=false),document(true,false,alignment=PdfVerticalAlignment.CENTER))) {
            assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,assertThrows(PdfRenderException::class.java) {engine.render(doc,fonts)}.code)
        }
        inspect(engine.render(document(false,false,split=false),fonts))
    }

    @Test fun `fragment state stays request local with a shared engine and fonts`() {
        val engine=PdfEngine();val fonts=FoundationLetter.fonts();val pool=Executors.newFixedThreadPool(3)
        try {
            val jobs=(0..5).map {i->pool.submit {inspect(engine.render(document(true,i%2==0,"JOB$i"),fonts),"JOB$i",requireBorders=true)}}
            jobs.forEach {it.get(60,TimeUnit.SECONDS)}
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS))}
    }
}

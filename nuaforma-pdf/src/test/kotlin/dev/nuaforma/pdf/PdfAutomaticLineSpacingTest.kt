package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfAutomaticLineSpacingTest {
    private val style=PdfTextStyle("regular",11f)
    private fun format(multiple:Float=1f)=PdfParagraphFormat(automaticLineSpacing=PdfAutomaticLineSpacing(multiple))
    private fun paragraph(text:String,multiple:Float=1f)=PdfParagraph(listOf(PdfTextRun(text,style)),0f,0f,format(multiple))
    private val fonts=FoundationLetter.fonts()
    private fun baselines(pdf:org.apache.pdfbox.pdmodel.PDDocument):List<Float> {
        val y=mutableListOf<Float>()
        object:PDFTextStripper() {override fun processTextPosition(p:TextPosition) {if(p.unicode=="Q")y+=p.yDirAdj;super.processTextPosition(p)}}.getText(pdf)
        return y
    }
    @Test fun `natural font line metrics determine actual baseline advances`() {
        for((multiple,expected)in listOf(1f to 12.649f,1.5f to 18.973f,2f to 25.298f)) {
            val p=paragraph("Q01 Q02 Q03",multiple)
            Loader.loadPDF(PdfEngine().render(PdfDocument(PdfPage(70f,200f,PdfMargins(20f,20f,20f,20f)),listOf(p),PdfFlow()),fonts)).use {pdf ->
                val y=baselines(pdf);assertEquals(3,y.size);assertEquals(expected,y[1]-y[0],.02f);assertEquals(expected,y[2]-y[1],.02f)
                assertEquals(30.318f,y[0],.02f)
            }
        }
    }
    @Test fun `automatic boxes paginate and retain all content exactly once`() {
        for((multiple,pages)in listOf(1f to 2,2f to 3)) {
            val doc=PdfDocument(PdfPage(200f,100f,PdfMargins(20f,20f,20f,20f)),(1..6).map {paragraph("Item$it",multiple)},PdfFlow())
            Loader.loadPDF(PdfEngine().render(doc,fonts)).use {pdf ->
                assertEquals(pages,pdf.numberOfPages)
                val text=PDFTextStripper().getText(pdf);for(n in 1..6)assertEquals(1,Regex("Item$n").findAll(text).count())
            }
        }
    }
    @Test fun `invalid conflicting and mixed natural boxes reject explicitly`() {
        for(value in listOf(0f,.5f,11f,Float.NaN,Float.POSITIVE_INFINITY))assertThrows(IllegalArgumentException::class.java) {PdfAutomaticLineSpacing(value)}
        assertThrows(IllegalArgumentException::class.java) {PdfParagraphFormat(exactLineHeight=14f,automaticLineSpacing=PdfAutomaticLineSpacing())}
        val p=PdfParagraph(listOf(PdfTextRun("Large",style),PdfTextRun("Small",PdfTextStyle("regular",9f))),0f,0f,format())
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,assertThrows(PdfRenderException::class.java) {PdfEngine().render(PdfDocument(PdfPage(300f,200f,PdfMargins(20f,20f,20f,20f)),listOf(p),PdfFlow()),fonts)}.code)
    }
    @Test fun `missing natural metrics fail without substituting a font`() {
        val bytes=fonts.first().bytes();val buffer=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        for(i in 0 until (buffer.getShort(4).toInt() and 65535))if(buffer.getInt(12+i*16)==0x68686561)buffer.putShort(buffer.getInt(20+i*16)+8,(-1).toShort())
        val altered=PdfFont("regular",bytes)
        assertEquals(PdfFailureCode.INVALID_RESOURCE,assertThrows(PdfRenderException::class.java) {PdfEngine().render(PdfDocument(PdfPage(300f,200f,PdfMargins(20f,20f,20f,20f)),listOf(paragraph("Content")),PdfFlow()),listOf(altered))}.code)
    }
    @Test fun `shared automatic policy and engine preserve concurrent request text`() {
        val executor=Executors.newFixedThreadPool(4);val engine=PdfEngine();val policy=format(1.5f)
        try {
            val jobs=(1..8).map {n->executor.submit<String> {
                val p=PdfParagraph(listOf(PdfTextRun("Request$n",style)),0f,0f,policy)
                Loader.loadPDF(engine.render(PdfDocument(PdfPage(300f,200f,PdfMargins(20f,20f,20f,20f)),listOf(p),PdfFlow()),fonts)).use {PDFTextStripper().getText(it)}
            }}
            jobs.forEachIndexed {i,job->assertEquals(listOf("Request${i+1}"),Regex("Request\\d+").findAll(job.get(30,TimeUnit.SECONDS)).map {it.value}.toList())}
        }finally {executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS))}
    }
}

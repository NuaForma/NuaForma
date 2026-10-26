package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfTabsTest {
    private val style=PdfTextStyle("regular",10f)
    private val fonts=FoundationLetter.fonts()
    private val page=PdfPage(260f,160f,PdfMargins(20f,20f,20f,20f))
    private fun p(runs:List<PdfInline>,settings:PdfTabSettings=PdfTabSettings())=PdfParagraph(runs,0f,0f,PdfParagraphFormat(exactLineHeight=14f,tabs=settings))
    private fun positions(pdf:org.apache.pdfbox.pdmodel.PDDocument):List<TextPosition> {
        val positions=mutableListOf<TextPosition>()
        object:PDFTextStripper() {override fun processTextPosition(text:TextPosition) {positions+=text;super.processTextPosition(text)}}.getText(pdf)
        return positions
    }

    @Test fun `tab settings snapshot and sort stops and reject invalid defaults`() {
        val stops=mutableListOf(PdfTabStop(100f),PdfTabStop(36f));val settings=PdfTabSettings(stops);stops.clear()
        assertEquals(listOf(36f,100f),settings.stops.map {it.position})
        assertThrows(UnsupportedOperationException::class.java) {(settings.stops as MutableList<*>).clear()}
        assertThrows(IllegalArgumentException::class.java) {PdfTabSettings(listOf(PdfTabStop(36f),PdfTabStop(36f)))}
        assertThrows(IllegalArgumentException::class.java) {PdfTabSettings(defaultInterval=0f)}
        assertThrows(IllegalArgumentException::class.java) {PdfTabSettings(decimalSeparator=";")}
        assertThrows(IllegalArgumentException::class.java) {PdfTabStop(Float.NaN)}
    }

    @Test fun `left right center and decimal alignment use actual glyph positions`() {
        for (kind in PdfTabAlignment.entries) {
            val block=p(listOf(PdfTextRun("L",style),PdfTab(style),PdfTextRun("123,45",style)),PdfTabSettings(listOf(PdfTabStop(100f,kind)),decimalSeparator=","))
            Loader.loadPDF(PdfEngine().render(PdfDocument(page,listOf(block)),fonts)).use {pdf ->
                val chars=positions(pdf).filter {it.unicode !in listOf("L"," ")}
                val actual=when(kind) {
                    PdfTabAlignment.LEFT->chars.first().xDirAdj
                    PdfTabAlignment.RIGHT->chars.last().xDirAdj+chars.last().widthDirAdj
                    PdfTabAlignment.CENTER->(chars.first().xDirAdj+chars.last().xDirAdj+chars.last().widthDirAdj)/2
                    PdfTabAlignment.DECIMAL->chars.single {it.unicode==","}.xDirAdj
                }
                assertEquals(120f,actual,.02f,kind.name)
            }
        }
    }

    @Test fun `decimal tabs without a separator align the full value before the stop`() {
        val block=p(listOf(PdfTextRun("L",style),PdfTab(style),PdfTextRun("7",style)),PdfTabSettings(listOf(PdfTabStop(100f,PdfTabAlignment.DECIMAL)),decimalSeparator=","))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page,listOf(block)),fonts)).use {pdf ->
            val seven=positions(pdf).single {it.unicode=="7"};assertEquals(120f,seven.xDirAdj+seven.widthDirAdj,.02f)
        }
    }

    @Test fun `consecutive default tabs advance without inserting source spaces`() {
        val block=p(listOf(PdfTab(style),PdfTab(style),PdfTextRun("Body",style)))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page,listOf(block)),fonts)).use {pdf ->assertEquals(92f,positions(pdf).single {it.unicode=="B"}.xDirAdj,.02f)}
        assertEquals(2,block.runs.count {it is PdfTab})
    }

    @Test fun `left tab content wraps and paginates with ordinary continuation indentation`() {
        val text=(1..8).joinToString(" "){"Q%02dWWWW".format(java.util.Locale.ROOT,it)}
        val block=p(listOf(PdfTextRun("L",style),PdfTab(style),PdfTextRun(text,style)),PdfTabSettings(listOf(PdfTabStop(60f))))
        Loader.loadPDF(PdfEngine().render(PdfDocument(PdfPage(160f,96f,page.margins),listOf(block),PdfFlow()),fonts)).use {pdf ->
            assertEquals(2,pdf.numberOfPages)
            val all=PDFTextStripper().getText(pdf)
            for(i in 1..8)assertEquals(1,Regex("Q%02dWWWW".format(java.util.Locale.ROOT,i)).findAll(all).count())
            val q=positions(pdf).filter {it.unicode=="Q"};assertEquals(80f,q[0].xDirAdj,.02f);assertEquals(20f,q[1].xDirAdj,.02f)
        }
    }

    @Test fun `leaders have artifact markup embedded fonts and preserve body text`() {
        for(leader in listOf(PdfTabLeader.DOT,PdfTabLeader.HYPHEN,PdfTabLeader.UNDERSCORE)) {
            val block=p(listOf(PdfTextRun("Label",style),PdfTab(style),PdfTextRun("Value",style)),PdfTabSettings(listOf(PdfTabStop(180f,PdfTabAlignment.RIGHT,leader))))
            Loader.loadPDF(PdfEngine().render(PdfDocument(page,listOf(block)),fonts)).use {pdf ->
                val content=pdf.getPage(0).contents.use {it.readBytes().toString(Charsets.ISO_8859_1)}
                assertTrue(Regex("/Artifact\\s+BMC").containsMatchIn(content));assertTrue(content.contains("EMC"))
                assertTrue(PDFTextStripper().getText(pdf).contains("Value"))
                assertTrue(pdf.getPage(0).resources.fontNames.all {pdf.getPage(0).resources.getFont(it).isEmbedded})
            }
        }
    }

    @Test fun `overlapping aligned fields and tab or leader resource excess fail explicitly`() {
        val tooWide=p(listOf(PdfTextRun("Label",style),PdfTab(style),PdfTextRun("WWWWWWWWWWWWWWWWWWWW",style)),PdfTabSettings(listOf(PdfTabStop(50f,PdfTabAlignment.RIGHT))))
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW,assertThrows(PdfRenderException::class.java) {PdfEngine().render(PdfDocument(page,listOf(tooWide)),fonts)}.code)
        assertThrows(IllegalArgumentException::class.java) {p(List(33){PdfTab(style)})}
        assertThrows(IllegalArgumentException::class.java) {PdfDocument(page,List(667){p(List(3){PdfTab(style)})})}
        val small=PdfTextStyle("regular",1f)
        val huge=p(listOf(PdfTextRun("L",small),PdfTab(small),PdfTextRun("V",small)),PdfTabSettings(listOf(PdfTabStop(10_000f,PdfTabAlignment.RIGHT,PdfTabLeader.DOT))))
        assertEquals(PdfFailureCode.RESOURCE_LIMIT,assertThrows(PdfRenderException::class.java) {PdfEngine().render(PdfDocument(PdfPage(14_400f,200f,page.margins),listOf(huge)),fonts)}.code)
        val many=p(listOf(PdfTextRun("L",small),PdfTab(small),PdfTextRun("V",small)),PdfTabSettings(listOf(PdfTabStop(1100f,PdfTabAlignment.RIGHT,PdfTabLeader.DOT))))
        assertEquals(PdfFailureCode.RESOURCE_LIMIT,assertThrows(PdfRenderException::class.java) {PdfEngine().render(PdfDocument(PdfPage(1200f,1000f,page.margins),List(40){many}),fonts)}.code)
    }

    @Test fun `shared settings and engine retain request text under concurrent tab rendering`() {
        val engine=PdfEngine();val settings=PdfTabSettings(listOf(PdfTabStop(180f,PdfTabAlignment.RIGHT,PdfTabLeader.DOT)))
        val executor=Executors.newFixedThreadPool(4)
        try {
            val jobs=(1..8).map {n->executor.submit<String> {
                val block=p(listOf(PdfTextRun("Label",style),PdfTab(style),PdfTextRun("Request$n",style)),settings)
                Loader.loadPDF(engine.render(PdfDocument(page,listOf(block)),fonts)).use {PDFTextStripper().getText(it)}
            }}
            jobs.forEachIndexed {i,job->assertEquals(listOf("Request${i+1}"),Regex("Request\\d+").findAll(job.get(30,TimeUnit.SECONDS)).map {it.value}.toList())}
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS))}
    }
}

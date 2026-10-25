package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfNumberingTest {
    private fun rule(pattern: String, format: PdfNumberFormat = PdfNumberFormat.DECIMAL, start: Int = 1, restart: Int? = null, legal: Boolean = false) = PdfListLevel(format, pattern, start, restart, legal)
    private fun resolve(levels: List<PdfListLevel>, steps: List<Int>, id: String = "list") = PdfNumbering.resolve(listOf(PdfListDefinition(id, levels)), steps.map { PdfListStep(id, it) }).map { it.text }

    @Test fun `nested counters restart after ancestors while instances remain independent`() {
        val levels = listOf(rule("%1."), rule("%1.%2."), rule("%1.%2.%3."))
        assertEquals(listOf("1.", "1.1.", "1.1.1.", "1.1.2.", "1.2.", "1.2.1.", "2.", "2.1.", "2.1.1."), resolve(levels, listOf(0,1,2,2,1,2,0,1,2)))
        val values = PdfNumbering.resolve(listOf(PdfListDefinition("a", levels), PdfListDefinition("b", levels)), listOf(PdfListStep("a",0),PdfListStep("b",0),PdfListStep("a",0)))
        assertEquals(listOf("1.","1.","2."),values.map { it.text })
        assertEquals(listOf("a","b","a"),values.map { it.instanceId })
    }

    @Test fun `continued instances can share a sequence with explicit restarts`() {
        val a=PdfListDefinition("a",listOf(rule("%1")),"shared")
        val b=PdfListDefinition("b",listOf(rule("%1",start=7)),"shared")
        val steps=listOf(PdfListStep("a",0),PdfListStep("a",0),PdfListStep("b",0,true),PdfListStep("b",0),PdfListStep("a",0))
        assertEquals(listOf("1","2","7","8","9"),PdfNumbering.resolve(listOf(a,b),steps).map { it.text })
    }

    @Test fun `custom restart and never restart preserve qualified counters`() {
        val levels = listOf(rule("%1."),rule("%2)",PdfNumberFormat.LOWER_LETTER),rule("%3)",PdfNumberFormat.LOWER_ROMAN,restart=-1))
        assertEquals(listOf("1.","a)","i)","ii)","2.","a)","iii)"),resolve(levels,listOf(0,1,2,2,0,1,2)))
        val custom= listOf(rule("%1"),rule("%2"),rule("%3",restart=0))
        assertEquals(listOf("1","1","1","2","2","2","1","1"),resolve(custom,listOf(0,1,2,1,2,0,1,2)))
    }

    @Test fun `letter rollover matches Word repeated letters rather than spreadsheet column names`() {
        assertEquals(listOf("y)","z)","aa)","bb)"),resolve(listOf(rule("%1)",PdfNumberFormat.LOWER_LETTER,25)),List(4){0}))
        assertEquals(listOf("Z","AA","BB"),resolve(listOf(rule("%1",PdfNumberFormat.UPPER_LETTER,26)),List(3){0}))
        assertEquals(listOf("VIII","IX","X","XI"),resolve(listOf(rule("%1",PdfNumberFormat.UPPER_ROMAN,8)),List(4){0}))
        assertEquals(listOf("03","04"),resolve(listOf(rule("%1",PdfNumberFormat.DECIMAL_ZERO,3)),List(2){0}))
        assertEquals(listOf("0","1"),resolve(listOf(rule("%1",start=0)),List(2){0}))
        assertEquals(listOf("I","1.1","1.2"),resolve(listOf(rule("%1",PdfNumberFormat.UPPER_ROMAN),rule("%1.%2",PdfNumberFormat.LOWER_LETTER,legal=true)),listOf(0,1,1)))
    }

    @Test fun `definitions snapshot inputs and returned values cannot be mutated`() {
        val levels=mutableListOf(rule("%1"));val definition=PdfListDefinition("list",levels);levels.clear()
        val values=PdfNumbering.resolve(listOf(definition),listOf(PdfListStep("list",0)))
        assertEquals("1",values.single().text)
        assertThrows(UnsupportedOperationException::class.java) { (definition.levels as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (values as MutableList<*>).clear() }
    }

    @Test fun `invalid references patterns impossible formats and budgets fail explicitly`() {
        val invalid = listOf(listOf(rule("%2")) to listOf(0),listOf(rule("%0")) to listOf(0),
            listOf(rule("%1",PdfNumberFormat.LOWER_ROMAN,4000)) to listOf(0),listOf(rule("%1",PdfNumberFormat.LOWER_LETTER,0)) to listOf(0),
            listOf(rule("%1",start=1_000_000)) to listOf(0,0),listOf(rule("%1"),rule("%2")) to listOf(1))
        for ((levels,steps) in invalid) assertThrows(IllegalArgumentException::class.java) { resolve(levels,steps) }
        assertThrows(IllegalArgumentException::class.java) { PdfNumbering.resolve(emptyList(),listOf(PdfListStep("missing",0))) }
        assertThrows(IllegalArgumentException::class.java) { resolve(listOf(rule("%1")),List(2001){0}) }
        val bullets=PdfNumbering.resolve(listOf(PdfListDefinition("b",listOf(rule("•",PdfNumberFormat.BULLET)))),listOf(PdfListStep("b",0)))
        assertNull(bullets.single().ordinal)
    }

    @Test fun `labels are separate from body with aligned first and continuation lines and no repeated page label`() {
        val style=PdfTextStyle("regular",10f)
        val label=PdfListLabel(PdfListValue("list",0,10,"10."),style,PdfAlignment.RIGHT,tabStop=36f)
        val p=PdfParagraph(listOf(PdfTextRun((1..8).joinToString(" "){"Q%02dWWWW".format(java.util.Locale.ROOT,it)},style)),0f,0f,
            PdfParagraphFormat(leftIndent=36f,firstLineIndent=-18f,exactLineHeight=14f),label)
        val page=PdfPage(160f,96f,PdfMargins(20f,20f,20f,20f))
        Loader.loadPDF(PdfEngine().render(PdfDocument(page,listOf(p),PdfFlow()),FoundationLetter.fonts())).use { pdf ->
            assertEquals(2,pdf.numberOfPages)
            val strips=(1..2).map { n -> PDFTextStripper().apply { startPage=n;endPage=n }.getText(pdf) }
            assertTrue(strips[0].contains("10."));assertFalse(strips[1].contains("10."))
            val positions=mutableListOf<org.apache.pdfbox.text.TextPosition>()
            object:PDFTextStripper() { override fun processTextPosition(text:org.apache.pdfbox.text.TextPosition) { positions+=text;super.processTextPosition(text) } }.getText(pdf)
            val q=positions.filter { it.unicode=="Q" };assertEquals(8,q.size);assertTrue(q.all { kotlin.math.abs(it.xDirAdj-56f)<.01f })
            assertTrue(positions.first().xDirAdj<38f)
        }
    }

    @Test fun `suffixes and marker bounds are measured without inserting body spaces`() {
        val style=PdfTextStyle("regular",10f)
        for (suffix in PdfListSuffix.entries) {
            val label=PdfListLabel(PdfListValue("a",0,1,"1)"),style,suffix=suffix,tabStop=36f)
            val p=PdfParagraph(listOf(PdfTextRun("Body",style)),0f,0f,PdfParagraphFormat(leftIndent=36f,firstLineIndent=-18f,exactLineHeight=14f),label)
            assertEquals("Body",(p.runs.single() as PdfTextRun).text)
            Loader.loadPDF(PdfEngine().render(PdfDocument(PdfPage(200f,150f,PdfMargins(20f,20f,20f,20f)),listOf(p)),FoundationLetter.fonts())).use { pdf -> assertTrue(PDFTextStripper().getText(pdf).contains("Body")) }
        }
        val bad=PdfParagraph(listOf(PdfTextRun("Body",style)),format=PdfParagraphFormat(exactLineHeight=14f),listLabel=PdfListLabel(PdfListValue("a",0,1,"12345"),style,PdfAlignment.RIGHT))
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW,assertThrows(PdfRenderException::class.java) { PdfEngine().render(PdfDocument(PdfPage(200f,150f,PdfMargins(20f,20f,20f,20f)),listOf(bad)),FoundationLetter.fonts()) }.code)
    }

    @Test fun `labels that overrun their explicit tab advance to the declared default grid`() {
        val style=PdfTextStyle("regular",10f)
        val label=PdfListLabel(PdfListValue("a",0,123,"123."),style,tabStop=36f,defaultTabInterval=24f)
        val p=PdfParagraph(listOf(PdfTextRun("Body",style)),0f,0f,PdfParagraphFormat(leftIndent=36f,firstLineIndent=-18f,exactLineHeight=14f),label)
        Loader.loadPDF(PdfEngine().render(PdfDocument(PdfPage(200f,150f,PdfMargins(20f,20f,20f,20f)),listOf(p)),FoundationLetter.fonts())).use { pdf ->
            var left:Float?=null
            object:PDFTextStripper() { override fun processTextPosition(text:org.apache.pdfbox.text.TextPosition) {if(text.unicode=="B")left=text.xDirAdj;super.processTextPosition(text)} }.getText(pdf)
            assertEquals(68f,requireNotNull(left),.01f)
        }
    }

    @Test fun `cancellation retains interrupt state and labels participate in aggregate text budgets`() {
        try {
            Thread.currentThread().interrupt()
            assertEquals(PdfFailureCode.CANCELLED,assertThrows(PdfRenderException::class.java) { resolve(listOf(rule("%1")),listOf(0)) }.code)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        val style=PdfTextStyle("regular",10f)
        val label=PdfListLabel(PdfListValue("a",0,1,"1."),style,tabStop=36f)
        val p=PdfParagraph(listOf(PdfTextRun("a".repeat(100_000),style)),listLabel=label)
        assertThrows(IllegalArgumentException::class.java) { PdfDocument(PdfPage(200f,150f,PdfMargins(20f,20f,20f,20f)),listOf(p)) }
        val missing=PdfParagraph(listOf(PdfTextRun("Body",style)),format=PdfParagraphFormat(leftIndent=36f,firstLineIndent=-18f,exactLineHeight=14f),listLabel=label.copy(style=PdfTextStyle("missing",10f)))
        assertEquals(PdfFailureCode.FONT_UNAVAILABLE,assertThrows(PdfRenderException::class.java) {PdfEngine().render(PdfDocument(PdfPage(200f,150f,PdfMargins(20f,20f,20f,20f)),listOf(missing)),FoundationLetter.fonts())}.code)
    }

    @Test fun `numbering and rendering are request local under shared concurrent use`() {
        val engine=PdfEngine();val fonts=FoundationLetter.fonts();val executor=Executors.newFixedThreadPool(4)
        try {
            val jobs=(1..8).map { n -> executor.submit<String> {
                val value=PdfNumbering.resolve(listOf(PdfListDefinition("same",listOf(rule("%1.")))),listOf(PdfListStep("same",0))).single()
                val style=PdfTextStyle("regular",10f)
                val p=PdfParagraph(listOf(PdfTextRun("Request$n",style)),format=PdfParagraphFormat(leftIndent=36f,firstLineIndent=-18f,exactLineHeight=14f),listLabel=PdfListLabel(value,style,tabStop=36f))
                Loader.loadPDF(engine.render(PdfDocument(PdfPage(200f,150f,PdfMargins(20f,20f,20f,20f)),listOf(p)),fonts)).use { PDFTextStripper().getText(it) }
            } }
            jobs.forEachIndexed { i,job -> val text=job.get(30,TimeUnit.SECONDS);assertTrue(text.contains("1."));assertEquals(listOf("Request${i+1}"),Regex("Request\\d+").findAll(text).map { it.value }.toList()) }
        } finally { executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS)) }
    }
}

package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfInternationalTextTest {
    private val samples=listOf("ru-RU" to "Отчёт о ходе проекта. Ёлка, сумма 1 234,56.","uk-UA" to "Київ, Україна. Ґрунт, єдність, їжа.","el-GR" to "Έκθεση προόδου. Αθήνα, στοιχεία, μορφοποίηση.")
    private fun document(language:String,text:String)=PdfDocument(PdfPage(400f,200f,PdfMargins(20f,20f,20f,20f)),listOf(PdfParagraph(listOf(PdfTextRun(text,PdfTextStyle("regular",11f,languageTag=language))),0f,0f,PdfParagraphFormat(exactLineHeight=16f))),PdfFlow())
    @Test fun `Cyrillic Ukrainian and Greek text survives actual embedded-font PDFs`() {
        for((language,text)in samples)Loader.loadPDF(PdfEngine().render(document(language,text),FoundationLetter.fonts())).use {pdf ->
            assertEquals(text,PDFTextStripper().getText(pdf).trim());assertEquals(1,pdf.numberOfPages)
            assertTrue(pdf.getPage(0).resources.fontNames.all {pdf.getPage(0).resources.getFont(it).isEmbedded})
        }
    }
    @Test fun `language is retained in actual marked text without changing Unicode`() {
        val (language,text)=samples[1]
        Loader.loadPDF(PdfEngine().render(document(language,text),FoundationLetter.fonts())).use {pdf ->
            val operators=pdf.getPage(0).contents.use {it.readBytes().toString(Charsets.ISO_8859_1)}
            assertTrue(operators.contains("/Lang (uk-UA)"));assertTrue(operators.contains("BDC"));assertTrue(operators.contains("EMC"))
            assertEquals(text,PDFTextStripper().getText(pdf).trim())
        }
        for(tag in listOf("ru RU","","a","ru-123456789","x".repeat(65)))assertThrows(IllegalArgumentException::class.java) {PdfTextStyle("regular",11f,languageTag=tag)}
    }
    @Test fun `complex scripts combining marks and missing glyphs still reject explicitly`() {
        for(text in listOf("مرحبا","שלום","α\u0301","а\u0483"))assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,assertThrows(PdfRenderException::class.java) {PdfEngine().render(document("und",text),FoundationLetter.fonts())}.code)
        assertEquals(PdfFailureCode.MISSING_GLYPH,assertThrows(PdfRenderException::class.java) {PdfEngine().render(document("ru-RU","\uA640"),FoundationLetter.fonts())}.code)
    }
    @Test fun `shared engine and fonts keep script language and request text separate`() {
        val executor=Executors.newFixedThreadPool(4);val engine=PdfEngine();val fonts=FoundationLetter.fonts()
        try {
            val jobs=(0..8).map {n->executor.submit<Pair<String,String>> {
                val (language,text)=samples[n%3]
                Loader.loadPDF(engine.render(document(language,"Request$n $text"),fonts)).use {pdf ->
                    PDFTextStripper().getText(pdf).trim() to pdf.getPage(0).contents.use {it.readBytes().toString(Charsets.ISO_8859_1)}
                }
            }}
            jobs.forEachIndexed {n,job->val (text,operators)=job.get(30,TimeUnit.SECONDS);assertEquals("Request$n ${samples[n%3].second}",text);assertTrue(operators.contains("/Lang (${samples[n%3].first})"))}
        }finally {executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS))}
    }
}

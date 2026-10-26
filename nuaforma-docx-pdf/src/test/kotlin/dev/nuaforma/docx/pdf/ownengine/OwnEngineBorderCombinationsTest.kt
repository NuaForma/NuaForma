package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OwnEngineBorderCombinationsTest {
    private fun resource(name:String)=requireNotNull(javaClass.getResourceAsStream(name)).use {it.readBytes()}
    private fun source(profile:String)=resource("/own-engine-border-combinations/$profile/source.docx")
    private fun fonts()=listOf("regular" to "Regular","bold" to "Bold","italic" to "Italic").map {(id,name)->PdfFont(id,resource("/own-engine-fonts/LiberationSans-$name.ttf"))}

    @ParameterizedTest @ValueSource(strings=["merged","nested","pages","letter"])
    fun `consistent borders survive spans nesting repeated headers and Letter pages`(profile:String) {
        val document=OwnEngineDocxReader().read(source(profile))
        val table=document.blocks.filterIsInstance<PdfTable>().single()
        assertTrue(table.rows.flatMap {it.cells}.all {it.borders!=null})
        if(profile=="merged") assertTrue(table.rows.flatMap {it.cells}.any {it.rowSpan>1})
        if(profile=="nested") assertEquals(10,table.rows.flatMap {it.cells}.flatMap {it.blocks}.count {it is PdfTable})
        Loader.loadPDF(PdfEngine().render(document,fonts())).use {pdf->
            assertEquals(if(profile in listOf("merged","nested")) 2 else 3,pdf.numberOfPages)
            val text=PDFTextStripper().getText(pdf)
            val markers=when(profile) {
                "merged"->(1..32).map {"M%02d".format(it)}
                "nested"->(1..10).flatMap {i->(1..3).map {j->"N%02d-%d".format(i,j)}}
                else->(1..28).map {"W%02d".format(it)}
            }
            for(marker in markers) assertEquals(1,Regex("\\b${Regex.escape(marker)}\\b").findAll(text).count(),marker)
            if(profile=="letter") {
                assertEquals(612f,pdf.getPage(0).mediaBox.width,.01f)
                assertEquals(792f,pdf.getPage(0).mediaBox.height,.01f)
                assertTrue(text.contains("Київ")&&text.contains("Έκθεση"))
            }
            for(i in 0 until pdf.numberOfPages) {
                val pageText=PDFTextStripper().apply {startPage=i+1;endPage=i+1}.getText(pdf)
                assertTrue(Regex("Pagina\\s+${i+1}\\b").containsMatchIn(pageText))
                for(font in pdf.getPage(i).resources.fontNames) assertTrue(pdf.getPage(i).resources.getFont(font).isEmbedded)
            }
        }
    }

    @Test fun `oversized non-splitting custom rows stay explicitly unqualified`() {
        val document=OwnEngineDocxReader().read(source("split"))
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,assertThrows(PdfRenderException::class.java) {PdfEngine().render(document,fonts())}.code)
    }
}

package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OwnEngineBorderFragmentationTest {
    private fun resource(name:String)=requireNotNull(javaClass.getResourceAsStream(name)).use {it.readBytes()}
    private fun fonts()=listOf("regular" to "Regular","bold" to "Bold","italic" to "Italic").map {(id,name)->PdfFont(id,resource("/own-engine-fonts/LiberationSans-$name.ttf"))}

    @ParameterizedTest @ValueSource(strings=["a4","letter"])
    fun `explicitly splittable bordered rows retain every line and repeat only headers`(profile:String) {
        val source=resource("/own-engine-border-fragmentation/$profile/source.docx")
        val document=OwnEngineDocxReader().read(source)
        val table=document.blocks.filterIsInstance<PdfTable>().single()
        assertEquals(2,table.rows.count {it.allowSplit})
        assertTrue(table.rows.any {it.allowSplit&&it.cells.any {cell->cell.columnSpan==2}})
        Loader.loadPDF(PdfEngine().render(document,fonts())).use {pdf->
            assertEquals(5,pdf.numberOfPages)
            assertEquals(if(profile=="letter") 612f else 595.3f,pdf.getPage(0).mediaBox.width,.01f)
            val text=PDFTextStripper().getText(pdf)
            for((code,count) in listOf("A01" to 2,"S01" to 100,"K01" to 8,"S02" to 100,"Z01" to 2)) {
                for(line in 1..count) assertEquals(1,Regex("\\b$code-%03d\\b".format(line)).findAll(text).count(),"$code-$line")
                assertEquals(1,Regex("(?m)^$code(?:\\s|$)").findAll(text).count(),code)
            }
            assertEquals(5,Regex("Uitgevoerde controles").findAll(text).count())
            for(i in 0 until pdf.numberOfPages) {
                val content=PDFTextStripper().apply {startPage=i+1;endPage=i+1}.getText(pdf)
                assertTrue(Regex("Pagina\\s+${i+1}\\b").containsMatchIn(content))
                for(font in pdf.getPage(i).resources.fontNames) assertTrue(pdf.getPage(i).resources.getFont(font).isEmbedded)
            }
        }
    }
}

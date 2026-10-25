package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.util.zip.*

class OwnEngineCellBordersTest {
    private fun resource(name:String)=requireNotNull(javaClass.getResourceAsStream(name)).use {it.readBytes()}
    private fun source(profile:String)=resource("/own-engine-cell-borders/$profile/source.docx")
    private fun fonts()=listOf("regular" to "Regular","bold" to "Bold","italic" to "Italic").map {(id,name)->PdfFont(id,resource("/own-engine-fonts/LiberationSans-$name.ttf"))}

    @ParameterizedTest @ValueSource(strings=["colors","weights","outline"])
    fun `explicit cell borders preserve source content and produce embedded text PDFs`(profile:String) {
        val document=OwnEngineDocxReader().read(source(profile))
        val tables=document.blocks.filterIsInstance<PdfTable>()
        assertEquals(4,tables.size)
        val table=tables.first()
        assertTrue(table.rows.flatMap {it.cells}.all {it.borders!=null})
        if(profile=="outline") {
            val row=table.rows[0]
            assertNotNull(row.cells[0].borders!!.left)
            assertNull(row.cells[1].borders!!.left)
        }
        if(profile=="weights") assertEquals(4f,tables.last().rows[0].cells[2].borders!!.top!!.border.width)
        Loader.loadPDF(PdfEngine().render(document,fonts())).use {pdf->
            assertEquals(1,pdf.numberOfPages)
            val text=PDFTextStripper().getText(pdf)
            for(row in 1..12) for(col in 1..3) {
                val marker="B%02dC%d".format(row,col)
                assertEquals(1,Regex("\\b$marker\\b").findAll(text).count(),marker)
            }
            for(name in pdf.getPage(0).resources.fontNames) assertTrue(pdf.getPage(0).resources.getFont(name).isEmbedded)
        }
    }

    @Test fun `unqualified cell border styles sizes colors and diagonal edges reject`() {
        val original=source("colors")
        val anchor="<w:tcBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"00999F\"/>"
        for(replacement in listOf(
            "<w:tcBorders><w:top w:val=\"double\" w:sz=\"4\" w:color=\"00999F\"/>",
            "<w:tcBorders><w:top w:val=\"single\" w:sz=\"33\" w:color=\"00999F\"/>",
            "<w:tcBorders><w:top w:val=\"single\" w:sz=\"1.5\" w:color=\"00999F\"/>",
            "<w:tcBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\"/>",
            "<w:tcBorders><w:tl2br w:val=\"single\" w:sz=\"4\" w:color=\"00999F\"/>",
            "<w:tcBorders><w:top w:val=\"nil\" w:sz=\"NaN\"/>",
            "<w:tcBorders><w:top w:val=\"single\" w:sz=\"8\" w:color=\"00999F\"/>",
            "<w:tcBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"7A3040\"/>",
        )) {
            val changed=edit(original) {it.replaceFirst(anchor,replacement)}
            assertThrows(IllegalArgumentException::class.java) {OwnEngineDocxReader().read(changed)}
        }
    }

    @ParameterizedTest @ValueSource(strings=["colors","weights","outline"])
    fun `unqualified conflicting corner profiles reject rather than claiming Word fidelity`(profile:String) {
        val source=resource("/own-engine-border-conflicts/$profile/source.docx")
        val failure=assertThrows(IllegalArgumentException::class.java) {OwnEngineDocxReader().read(source)}
        assertTrue(failure.message!!.contains("Conflicting cell-border"))
    }

    private fun edit(source:ByteArray,transform:(String)->String):ByteArray {
        val output=ByteArrayOutputStream();var changed=false
        ZipInputStream(source.inputStream()).use {input->ZipOutputStream(output).use {out->
            while(true) {
                val entry=input.nextEntry?:break
                val bytes=input.readBytes();out.putNextEntry(ZipEntry(entry.name))
                if(entry.name=="word/document.xml") {val original=bytes.toString(Charsets.UTF_8);val replacement=transform(original);require(replacement!=original);out.write(replacement.toByteArray());changed=true}
                else out.write(bytes)
                out.closeEntry()
            }
        }}
        require(changed);return output.toByteArray()
    }
}

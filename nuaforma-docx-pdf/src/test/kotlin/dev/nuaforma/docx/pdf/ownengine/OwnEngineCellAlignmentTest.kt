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

class OwnEngineCellAlignmentTest {
    private fun resource(name:String)=requireNotNull(javaClass.getResourceAsStream(name)).use {it.readBytes()}
    private fun source(profile:String)=resource("/own-engine-cell-alignment/$profile/source.docx")
    private fun fonts()=listOf("regular" to "Regular","bold" to "Bold","italic" to "Italic").map {(id,name)->PdfFont(id,resource("/own-engine-fonts/LiberationSans-$name.ttf"))}

    @ParameterizedTest @ValueSource(strings=["ordinary","merged","nested"])
    fun `source cell alignment survives spans nesting and real PDF rendering`(profile:String) {
        val document=OwnEngineDocxReader().read(source(profile))
        val table=document.blocks.filterIsInstance<PdfTable>().single()
        val cells=table.rows.flatMap {it.cells}
        assertTrue(cells.any {it.verticalAlignment==PdfVerticalAlignment.CENTER})
        assertTrue(cells.any {it.verticalAlignment==PdfVerticalAlignment.BOTTOM})
        if(profile=="merged") {
            assertTrue(cells.any {it.rowSpan==3&&it.verticalAlignment==PdfVerticalAlignment.CENTER})
            assertTrue(cells.any {it.rowSpan==3&&it.verticalAlignment==PdfVerticalAlignment.BOTTOM})
            assertTrue(cells.any {it.columnSpan==2&&it.verticalAlignment==PdfVerticalAlignment.CENTER})
        }
        if(profile=="nested") assertEquals(5,cells.flatMap {it.blocks}.count {it is PdfTable})
        Loader.loadPDF(PdfEngine().render(document,fonts())).use {pdf->
            assertEquals(if(profile=="ordinary") 2 else 1,pdf.numberOfPages)
            val text=PDFTextStripper().getText(pdf)
            val markers=when(profile) {
                "ordinary"->(1..18).flatMap {listOf("T%02d".format(it),"C%02d".format(it),"B%02d".format(it))+(1..4).map {n->"A%02d-%d".format(it,n)}}
                "merged"->(1..4).flatMap {g->(1..3).map {n->"G$g$n"}}
                else->(1..5).flatMap {listOf("N${it}A","N${it}B")}
            }
            for(marker in markers) assertEquals(1,Regex("\\b${Regex.escape(marker)}\\b").findAll(text).count(),marker)
        }
    }

    @Test fun `unsupported alignment and conflicting vertical continuations reject explicitly`() {
        for(value in listOf("both","middle","")) {
            val changed=edit(source("ordinary")) {it.replaceFirst("<w:vAlign w:val=\"top\"/>",if(value.isEmpty()) "<w:vAlign/>" else "<w:vAlign w:val=\"$value\"/>")}
            assertThrows(IllegalArgumentException::class.java) {OwnEngineDocxReader().read(changed)}
        }
        val changed=edit(source("merged")) {it.replaceFirst("<w:vAlign w:val=\"center\"/>","<w:vAlign w:val=\"bottom\"/>")}
        assertThrows(IllegalArgumentException::class.java) {OwnEngineDocxReader().read(changed)}
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

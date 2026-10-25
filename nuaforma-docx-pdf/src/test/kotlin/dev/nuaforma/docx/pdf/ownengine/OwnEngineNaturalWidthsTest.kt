package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class OwnEngineNaturalWidthsTest {
    private fun resource(name:String)=requireNotNull(javaClass.getResourceAsStream(name)).use {it.readBytes()}
    private fun fonts()=listOf("regular" to "Regular","bold" to "Bold","italic" to "Italic").map {(id,name)->PdfFont(id,resource("/own-engine-fonts/LiberationSans-$name.ttf"))}

    @ParameterizedTest @ValueSource(strings=["a4","letter"])
    fun `natural column widths preserve paper geometry styles international text and repeated headers`(profile:String) {
        val doc=OwnEngineDocxReader().read(resource("/own-engine-natural-widths/$profile/source.docx"))
        val table=doc.blocks.filterIsInstance<PdfTable>().single()
        assertEquals(PdfTableWidthPolicy.FIT_CONTENTS,table.widthPolicy)
        assertEquals(41,table.rows.size)
        Loader.loadPDF(PdfEngine().render(doc,fonts())).use {pdf->
            assertEquals(2,pdf.numberOfPages)
            assertEquals(if(profile=="letter") 612f else 595.3f,pdf.getPage(0).mediaBox.width,.01f)
            val text=PDFTextStripper().getText(pdf)
            for(i in 1..40) assertEquals(1,Regex("\\bW%02d\\b".format(i)).findAll(text).count())
            assertEquals(2,Regex("Onderdeel").findAll(text).count())
            for(value in listOf("Documenttransformatieonderzoek","Контроль даних","Έλεγχος εγγράφων","Pagina 1","Pagina 2")) assertTrue(text.contains(value),value)
            for(page in pdf.pages) for(name in page.resources.fontNames) assertTrue(page.resources.getFont(name).isEmbedded)
        }
    }

    @Test fun `preferred percentage and inconsistent automatic source dimensions reject`() {
        val source=resource("/own-engine-natural-widths/a4/source.docx")
        val cases=listOf("<w:tblW w:w=\"0\" w:type=\"auto\"/>" to "<w:tblW w:w=\"5000\" w:type=\"pct\"/>",
            "<w:tcW w:w=\"0\" w:type=\"auto\"/>" to "<w:tcW w:w=\"100\" w:type=\"dxa\"/>",
            "<w:tcW w:w=\"0\" w:type=\"auto\"/>" to "<w:tcW w:w=\"1\" w:type=\"auto\"/>",
            "w:type=\"autofit\"" to "w:type=\"fixed\"")
        for((before,after) in cases) {
            val output=ByteArrayOutputStream();var changed=false
            ZipInputStream(source.inputStream()).use {input->ZipOutputStream(output).use {out->
                while(true) {
                    val entry=input.nextEntry?:break;val bytes=input.readBytes()
                    out.putNextEntry(ZipEntry(entry.name))
                    if(entry.name=="word/document.xml") {
                        val original=bytes.toString(Charsets.UTF_8);val replacement=original.replaceFirst(before,after)
                        require(replacement!=original);out.write(replacement.toByteArray(Charsets.UTF_8));changed=true
                    } else out.write(bytes)
                    out.closeEntry()
                }
            }}
            assertTrue(changed)
            assertThrows(IllegalArgumentException::class.java) {OwnEngineDocxReader().read(output.toByteArray())}
        }
    }
}

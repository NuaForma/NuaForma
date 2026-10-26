package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.*

class OwnEngineStylesTest {
    private fun resource(name:String)=requireNotNull(javaClass.getResourceAsStream(name)).use {it.readBytes()}
    private fun fixture()=resource("/own-engine-styles/source.docx")
    private fun read(bytes:ByteArray=fixture())=OwnEngineDocxReader().read(bytes)
    private fun text(p:PdfParagraph)=p.runs.filterIsInstance<PdfTextRun>().joinToString(""){it.text}
    private fun fonts()=listOf("regular" to "Regular","bold" to "Bold","italic" to "Italic","bolditalic" to "BoldItalic").map {(id,name)->PdfFont(id,resource("/own-engine-fonts/LiberationSans-$name.ttf"))}
    @Test fun `effective paragraph character theme and direct styles resolve in order`() {
        val ps=read().blocks.filterIsInstance<PdfParagraph>();fun item(id:String)=ps.single {text(it).startsWith(id)}
        fun face(id:String)=item(id).runs.filterIsInstance<PdfTextRun>().first().style
        assertEquals("bold",face("S01").fontId);assertEquals("bolditalic",face("S02").fontId);assertEquals("regular",face("S03").fontId)
        assertEquals("regular",face("S05").fontId)
        assertEquals(PdfColor(102,51,153),face("S03").color)
        assertEquals(listOf("regular","bolditalic","bold"),item("S04").runs.filterIsInstance<PdfTextRun>().map {it.style.fontId})
        assertEquals(listOf(1f,1.5f,2f),listOf("A02","A03","A04").map {item(it).format.automaticLineSpacing!!.multiple})
        assertNull(item("A02").format.exactLineHeight)
        assertEquals(0f,item("C01").spaceAfter);assertEquals(0f,item("C02").spaceBefore);assertEquals(0f,item("C02").spaceAfter)
        assertEquals(12f,item("C03").spaceAfter)
    }
    @Test fun `table base styles and direct run overrides reach the neutral cells`() {
        val table=read().blocks.filterIsInstance<PdfTable>().single()
        val first=table.rows.first().cells
        assertEquals("bolditalic",first[0].paragraphs.first().runs.filterIsInstance<PdfTextRun>().first().style.fontId)
        assertEquals("regular",first[1].paragraphs.first().runs.filterIsInstance<PdfTextRun>().first().style.fontId)
        assertEquals(16f,first[0].paragraphs.first().format.exactLineHeight)
    }
    @Test fun `real PDF keeps two pages all markers fonts and Unicode text`() {
        Loader.loadPDF(PdfEngine().render(read(),fonts())).use {pdf ->
            assertEquals(2,pdf.numberOfPages)
            val all=PDFTextStripper().getText(pdf)
            for(id in listOf("A01","A02","A03","A04","S01","S02","S03","S04","S05","C01","C02","C03","C04","T10","T11","T20","T21","T30","T31","P01"))assertEquals(1,Regex(id).findAll(all).count(),id)
            assertTrue(all.contains("overschrijft"));assertTrue(pdf.pages.all {p->p.resources.fontNames.all {p.resources.getFont(it).isEmbedded}})
        }
    }
    @Test fun `missing themes cycles conditional tables and unsupported line policies reject`() {
        val cases=listOf(
            "word/theme/theme1.xml" to {s:String->s.replace("Liberation Sans","")},
            "word/styles.xml" to {s:String->s.replace("w:asciiTheme=\"minorHAnsi\"","w:asciiTheme=\"unknown\"")},
            "word/styles.xml" to {s:String->s.replace("<w:basedOn w:val=\"AutoBase\"/>","<w:basedOn w:val=\"AutoDerived\"/>")},
            "word/styles.xml" to {s:String->s.replace("<w:name w:val=\"TableBase\"/>","<w:name w:val=\"TableBase\"/><w:tblStylePr w:type=\"firstRow\"/>")},
            "word/document.xml" to {s:String->s.replace("w:lineRule=\"auto\"","w:lineRule=\"atLeast\"")})
        for((part,change)in cases)assertThrows(IllegalArgumentException::class.java) {read(mutate(part,change))}
    }
    private fun mutate(part:String,change:(String)->String):ByteArray {
        val result=ByteArrayOutputStream()
        ZipOutputStream(result).use {output->ZipInputStream(fixture().inputStream()).use {input->while(true) {val entry=input.nextEntry?:break;val bytes=input.readBytes();output.putNextEntry(ZipEntry(entry.name));output.write(if(entry.name==part)change(bytes.toString(Charsets.UTF_8)).also {require(it != bytes.toString(Charsets.UTF_8))}.toByteArray(Charsets.UTF_8) else bytes);output.closeEntry()}}}
        return result.toByteArray()
    }
}

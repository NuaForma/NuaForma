package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class OwnEngineListsTest {
    private fun resource(name:String)=requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun fixture()=resource("/own-engine-lists/source.docx")
    private fun fonts()=listOf("regular" to "Regular","bold" to "Bold","italic" to "Italic").map { (id,face)->PdfFont(id,resource("/own-engine-fonts/LiberationSans-$face.ttf")) }
    private fun text(p:PdfParagraph)=p.runs.filterIsInstance<PdfTextRun>().joinToString(""){it.text}

    @Test fun `native labels preserve source order restarts style inheritance and real outline levels`() {
        val p=OwnEngineDocxReader().read(fixture()).blocks.filterIsInstance<PdfParagraph>()
        fun item(id:String)=p.single { text(it).startsWith("$id ") }
        fun label(id:String)=requireNotNull(item(id).listLabel).value.text
        assertEquals(listOf("y)","z)","aa)","bb)"),(1..4).map { label("A%02d".format(it)) })
        assertEquals(listOf("7.","8.","9."),(1..3).map { label("R%02d".format(it)) })
        assertEquals("10.",label("D14"))
        assertEquals(listOf("03.","04.","05."),(1..3).map { label("Z%02d".format(it)) })
        assertEquals("0.",label("Z00"))
        assertEquals(listOf("1","1.1","1.1.1","1.1.2","1.2","1.2.1","2","2.1","2.1.1"),(1..9).map { label("C%02d".format(it)) })
        assertNull(item("C10").listLabel)
        assertEquals("4",label("C11"));assertEquals("4.1",label("C12"))
        assertEquals(1,item("C05").format.outlineLevel)
        assertEquals(1,requireNotNull(item("C05").listLabel).value.level)
        assertEquals(listOf("I.","1.1","1.2"),(1..3).map { label("L%02d".format(it)) })
        assertEquals("iii)",label("M07"))
        assertEquals(-36f,item("B06").format.firstLineIndent);assertEquals(72f,item("B06").format.leftIndent)
        assertEquals("bold",requireNotNull(item("S01").listLabel).style.fontId)
        assertEquals(PdfColor(22,54,93),requireNotNull(item("S01").listLabel).style.color)
        assertTrue(p.filter { it.listLabel!=null }.all { text(it).first().isLetter() })
    }

    @Test fun `all list items and labels survive pagination once with repeated page bands`() {
        val model=OwnEngineDocxReader().read(fixture())
        Loader.loadPDF(PdfEngine().render(model,fonts())).use { pdf ->
            assertEquals(8,pdf.numberOfPages)
            val pages=(1..pdf.numberOfPages).map { n->PDFTextStripper().apply { startPage=n;endPage=n }.getText(pdf) }
            val all=pages.joinToString(" ")
            for ((prefix,count) in listOf("B" to 6,"H" to 2,"D" to 14,"R" to 3,"Z" to 3,"A" to 4,"V" to 4,"S" to 2,"N" to 1,"M" to 7,"L" to 3,"C" to 12,"P" to 24)) {
                for (n in 1..count) assertEquals(1,Regex("\\b$prefix%02d\\b".format(n)).findAll(all).count(),"$prefix$n")
            }
            assertEquals(1,Regex("\\bZ00\\b").findAll(all).count())
            for (n in 98..121) assertEquals(1,Regex("\\b$n\\.").findAll(all).count(),"label $n")
            for (n in pages.indices) {assertTrue(pages[n].contains("Pagina ${n+1}"));assertEquals(1,pdf.getPage(n).resources.xObjectNames.count())}
        }
    }

    @Test fun `unsupported numbering links formats overrides references and inconsistent styles fail explicitly`() {
        val cases=listOf(
            "word/numbering.xml" to {s:String->s.replaceFirst("<w:multiLevelType", "<w:styleLink w:val=\"Normal\"/><w:multiLevelType")},
            "word/numbering.xml" to {s:String->s.replace("w:val=\"decimalZero\"","w:val=\"chineseCounting\"")},
            "word/numbering.xml" to {s:String->s.replaceFirst("<w:startOverride w:val=\"7\"/>","<w:lvl w:ilvl=\"0\"/>")},
            "word/numbering.xml" to {s:String->s.replaceFirst("w:abstractNumId=\"1\"","w:abstractNumId=\"2\"")},
            "word/document.xml" to {s:String->s.replace("w:numId w:val=\"8\"","w:numId w:val=\"999\"")},
            "word/styles.xml" to {s:String->s.replace("<w:ilvl w:val=\"1\"/>","<w:ilvl w:val=\"0\"/>")},
            "word/header1.xml" to {s:String->s.replaceFirst("<w:pPr>","<w:pPr><w:numPr><w:numId w:val=\"1\"/></w:numPr>")},
            "word/settings.xml" to {s:String->s.replace("w:val=\"720\"","w:val=\"0\"")},
        )
        for ((part,change) in cases) assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader().read(mutate(part,change)) }
    }

    @Test fun `unsupported marker scripts fail without substitute glyphs`() {
        val invalid=mutate("word/numbering.xml") { it.replace("w:val=\"•\"","w:val=\"ع\"") }
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,assertThrows(PdfRenderException::class.java) {PdfEngine().render(OwnEngineDocxReader().read(invalid),fonts())}.code)
    }

    private fun mutate(part:String,change:(String)->String):ByteArray {
        val result=ByteArrayOutputStream()
        ZipOutputStream(result).use { output->ZipInputStream(fixture().inputStream()).use { input->
            while(true) {val entry=input.nextEntry?:break;val bytes=input.readBytes();output.putNextEntry(ZipEntry(entry.name));output.write(if(entry.name==part)change(bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else bytes);output.closeEntry()}
        } }
        return result.toByteArray()
    }
}

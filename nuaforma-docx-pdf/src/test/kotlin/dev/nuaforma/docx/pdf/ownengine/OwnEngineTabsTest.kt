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

class OwnEngineTabsTest {
    private fun resource(name:String)=requireNotNull(javaClass.getResourceAsStream(name)).use {it.readBytes()}
    private fun fixture()=resource("/own-engine-tabs/source.docx")
    private fun fonts()=listOf("regular" to "Regular","bold" to "Bold","italic" to "Italic").map {(id,name)->PdfFont(id,resource("/own-engine-fonts/LiberationSans-$name.ttf"))}
    private fun text(p:PdfParagraph)=p.runs.filterIsInstance<PdfTextRun>().joinToString(""){it.text}

    @Test fun `typed controls retain inherited cleared and directly overridden stops`() {
        val ps=OwnEngineDocxReader().read(fixture()).blocks.filterIsInstance<PdfParagraph>()
        fun item(id:String)=ps.single {text(it).startsWith(id)}
        assertEquals(listOf(144f,432f),item("S01").format.tabs!!.stops.map {it.position})
        assertEquals(listOf(180f,432f),item("S02").format.tabs!!.stops.map {it.position})
        val direct=item("S03").format.tabs!!
        assertEquals(listOf(216f,432f),direct.stops.map {it.position})
        assertEquals(PdfTabAlignment.CENTER,direct.stops.first().alignment)
        assertEquals(PdfTabLeader.HYPHEN,direct.stops.last().leader)
        assertEquals(",",item("D01").format.tabs!!.decimalSeparator)
        assertEquals(36f,item("G01").format.tabs!!.defaultInterval)
        assertEquals(3,item("G01").runs.count {it is PdfTab})
        assertTrue(item("F01").runs.filterIsInstance<PdfTextRun>().map {it.style.fontId}.containsAll(listOf("bold","italic")))
    }

    @Test fun `all source fields survive in real PDFs with repeated bands`() {
        Loader.loadPDF(PdfEngine().render(OwnEngineDocxReader().read(fixture()),fonts())).use {pdf ->
            assertEquals(2,pdf.numberOfPages)
            val all=PDFTextStripper().getText(pdf)
            for((prefix,count)in listOf("R" to 2,"L" to 1,"C" to 1,"D" to 4,"F" to 1,"G" to 1,"W" to 1,"T" to 7,"S" to 3))
                for(n in 1..count)assertEquals(1,Regex("\\b$prefix%02d\\b".format(n)).findAll(all).count())
            for(value in listOf("Voorbeeld B.V.","NF-2026-0057","1.234,56","42,00","-123,45","9.876,54","27 oktober 2026","Geërfd"))assertTrue(all.contains(value),value)
            for(n in 1..2)assertTrue(PDFTextStripper().apply {startPage=n;endPage=n}.getText(pdf).contains("Pagina $n"))
        }
    }

    @Test fun `unsupported alignments leaders defaults and duplicate stops reject explicitly`() {
        val cases=listOf(
            "word/document.xml" to {s:String->s.replace("w:val=\"right\" w:pos=\"8640\"","w:val=\"bar\" w:pos=\"8640\"")},
            "word/document.xml" to {s:String->s.replace("w:leader=\"dot\"","w:leader=\"heavy\"")},
            "word/settings.xml" to {s:String->s.replace("w:val=\",\"","w:val=\";\"")},
            "word/document.xml" to {s:String->s.replaceFirst("<w:tabs>","<w:tabs><w:tab w:val=\"right\" w:pos=\"8640\"/>")},
        )
        for((part,change)in cases)assertThrows(IllegalArgumentException::class.java) {OwnEngineDocxReader().read(mutate(part,change))}
        val centered=mutate("word/document.xml") {it.replace("<w:tabs>","<w:jc w:val=\"center\"/><w:tabs>")}
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT,assertThrows(PdfRenderException::class.java) {PdfEngine().render(OwnEngineDocxReader().read(centered),fonts())}.code)
    }

    private fun mutate(part:String,change:(String)->String):ByteArray {
        val result=ByteArrayOutputStream()
        ZipOutputStream(result).use {output->ZipInputStream(fixture().inputStream()).use {input->
            while(true) {val entry=input.nextEntry?:break;val bytes=input.readBytes();output.putNextEntry(ZipEntry(entry.name));output.write(if(entry.name==part)change(bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else bytes);output.closeEntry()}
        }}
        return result.toByteArray()
    }
}

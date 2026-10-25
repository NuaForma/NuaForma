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

class OwnEngineNumberingTest {
    private fun resource(name: String)=requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun source(profile: String)=resource("/own-engine-numbering/$profile/source.docx")
    private fun fonts()=listOf("regular" to "Regular","bold" to "Bold","italic" to "Italic").map { (id,name)->PdfFont(id,resource("/own-engine-fonts/LiberationSans-$name.ttf")) }

    @ParameterizedTest @ValueSource(strings=["restart","offset","continue","shared"])
    fun `section numbers totals inherited variants and implicit padding match actual PDF content`(profile: String) {
        val model=OwnEngineDocxReader().read(source(profile))
        assertEquals(if(profile in listOf("restart","shared")) listOf(25,1,null) else if(profile=="offset") listOf(0,100,null) else listOf(2,null,null),model.pageSequences.map { it.numbering.start })
        assertTrue(model.pageSequences.all { !it.numbering.alignParity || profile!="shared" })
        val numbers=when(profile) { "restart"->listOf(25,26,27,null,1,2,3,4,5,6);"offset"->listOf(0,1,2,null,100,101,102,103,104,105);"shared"->listOf(25,26,27,1,2,3,4,5,6);else->listOf(2,3,4,5,6,7,8,9,10) }
        val labels=when(profile) { "restart"->listOf("FIRST","EVEN","ODD",null,"ODD","EVEN","ODD","EVEN","ODD","EVEN");"offset"->listOf("EVEN","ODD","EVEN",null,"FIRST","ODD","EVEN","ODD","EVEN","ODD");"shared"->List(9) { "ODD" };else->listOf("EVEN","ODD","EVEN","FIRST","EVEN","ODD","EVEN","ODD","EVEN") }
        Loader.loadPDF(PdfEngine().render(model,fonts())).use { pdf ->
            assertEquals(numbers.size,pdf.numberOfPages)
            for((index,number) in numbers.withIndex()) {
                val page=pdf.getPage(index)
                val text=PDFTextStripper().apply { startPage=index+1;endPage=index+1 }.getText(pdf)
                if(number==null) {
                    assertTrue(text.isBlank());assertEquals(792f,page.mediaBox.width);assertEquals(612f,page.mediaBox.height)
                    assertFalse(page.resources.fontNames.any());assertFalse(page.resources.xObjectNames.any());continue
                }
                val sectionTotal=if(index<3) 3 else if(index>=numbers.size-2) 2 else 4
                val expected=listOf(number.toString(),sectionTotal.toString(),numbers.size.toString())
                val fields=Regex("(?:Pagina|P)\\s+(\\d+)\\s*/\\s*S\\s+(\\d+)\\s*/\\s*T\\s+(\\d+)").findAll(text).map { it.groupValues.drop(1) }.toList()
                assertEquals(listOf(expected,expected),fields)
                assertTrue(text.contains("H-${labels[index]}") && text.contains("F-${labels[index]}"))
                assertFalse(text.contains("999"));assertEquals(1,page.resources.xObjectNames.count())
                assertTrue(page.resources.fontNames.all { page.resources.getFont(it).isEmbedded })
            }
            val text=PDFTextStripper().getText(pdf)
            for(number in 1..28) assertEquals(3,Regex("\\bW${number.toString().padStart(2,'0')}\\b").findAll(text).count())
        }
    }

    @Test fun `invalid start values nondecimal formats and chapter settings reject`() {
        val changes=listOf<(String)->String>(
            { it.replace("w:start=\"25\"","w:start=\"-1\"") },
            { it.replace("w:start=\"25\"","w:start=\"10000\"") },
            { it.replace("w:start=\"25\"","w:start=\"2147483648\"") },
            { it.replace("w:start=\"25\"","w:start=\"1.5\"") },
            { it.replace("w:fmt=\"decimal\"","w:fmt=\"lowerRoman\"") },
            { it.replace("w:fmt=\"decimal\"","w:fmt=\"decimal\" w:chapStyle=\"1\"") },
        )
        for(change in changes) assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader().read(mutate(source("restart"),"word/document.xml",change)) }
    }

    @Test fun `single section adapter retains its explicit starting number`() {
        val bytes=mutate(resource("/own-engine-report/source.docx"),"word/document.xml") { it.replace("</w:sectPr>","<w:pgNumType w:start=\"0\"/></w:sectPr>") }
        val model=OwnEngineDocxReader().read(bytes);assertEquals(0,model.pageSequences.single().numbering.start)
        Loader.loadPDF(PdfEngine().render(model,fonts())).use { pdf ->
            assertEquals(3,pdf.numberOfPages)
            for(number in 0..2) assertTrue(PDFTextStripper().apply { startPage=number+1;endPage=number+1 }.getText(pdf).contains("Pagina $number"))
        }
    }

    private fun mutate(bytes: ByteArray,part: String,change: (String)->String): ByteArray {
        val result=ByteArrayOutputStream();var changed=false
        ZipOutputStream(result).use { output->ZipInputStream(bytes.inputStream()).use { input->
            while(true) {
                val entry=input.nextEntry?:break;val value=input.readBytes();output.putNextEntry(ZipEntry(entry.name))
                val next=if(entry.name==part) change(value.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else value
                changed=changed || !value.contentEquals(next);output.write(next);output.closeEntry()
            }
        } }
        require(changed);return result.toByteArray()
    }
}

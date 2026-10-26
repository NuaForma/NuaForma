package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.*

class OwnEngineInternationalTest {
    private fun resource(name:String)=requireNotNull(javaClass.getResourceAsStream(name)).use {it.readBytes()}
    private fun fixture()=resource("/own-engine-international/source.docx")
    private fun fonts()=listOf("regular" to "Regular","bold" to "Bold","italic" to "Italic").map {(id,name)->PdfFont(id,resource("/own-engine-fonts/LiberationSans-$name.ttf"))}
    private fun text(p:PdfParagraph)=p.runs.filterIsInstance<PdfTextRun>().joinToString(""){it.text}
    @Test fun `source language survives inheritance and direct overrides`() {
        val changed=mutate("word/styles.xml") {it.replaceFirst("<w:rPr>","<w:rPr><w:lang w:val=\"en-GB\"/>")}
        val paragraphs=OwnEngineDocxReader().read(changed).blocks.filterIsInstance<PdfParagraph>()
        assertEquals("en-GB",paragraphs.first().runs.filterIsInstance<PdfTextRun>().first().style.languageTag)
        for((id,language)in listOf("RU01" to "ru-RU","UK01" to "uk-UA","EL01" to "el-GR")) {
            val runs=paragraphs.single {text(it).startsWith(id)}.runs.filterIsInstance<PdfTextRun>()
            assertEquals("en-US",runs.first().style.languageTag);assertEquals(language,runs.last().style.languageTag)
        }
    }
    @Test fun `two pages retain lists tables all markers and exact non-Latin text`() {
        Loader.loadPDF(PdfEngine().render(OwnEngineDocxReader().read(fixture()),fonts())).use {pdf ->
            assertEquals(2,pdf.numberOfPages);val all=PDFTextStripper().getText(pdf)
            for(id in listOf("RU01","UK01","EL01","M01","T01","T02","T03","L01","L02","L03"))assertEquals(1,Regex("\\b$id\\b").findAll(all).count(),id)
            for(value in listOf("Отчёт о ходе проекта.","Київ, Україна","Έκθεση προόδου.","1 234,56 грн.","L02 Перевірити дані","L03 Έλεγχος"))assertTrue(all.contains(value),value)
            assertTrue(all.contains("1. L01"));assertTrue(all.contains("2. L02"));assertTrue(all.contains("3. L03"))
        }
    }
    @Test fun `invalid and unqualified language source attributes fail explicitly`() {
        for(change in listOf<(String)->String>({it.replace("w:val=\"ru-RU\"","w:val=\"ru RU\"")},{it.replace("w:val=\"ru-RU\"","w:val=\"ru-RU\" w:unqualified=\"ar-SA\"")}))assertThrows(IllegalArgumentException::class.java) {OwnEngineDocxReader().read(mutate("word/document.xml",change))}
    }
    private fun mutate(part:String,change:(String)->String):ByteArray {
        val result=ByteArrayOutputStream()
        ZipOutputStream(result).use {output->ZipInputStream(fixture().inputStream()).use {input->while(true) {val entry=input.nextEntry?:break;val bytes=input.readBytes();output.putNextEntry(ZipEntry(entry.name));output.write(if(entry.name==part)change(bytes.toString(Charsets.UTF_8)).also {require(it!=bytes.toString(Charsets.UTF_8))}.toByteArray(Charsets.UTF_8) else bytes);output.closeEntry()}}}
        return result.toByteArray()
    }
}

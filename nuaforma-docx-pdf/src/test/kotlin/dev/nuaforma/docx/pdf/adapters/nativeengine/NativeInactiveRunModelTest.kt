package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTextRun
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NativeInactiveRunModelTest {

    @Test
    fun `disabled double strike preserves inherited single strike and unused active styles stay unused`() {
        val original = requireNotNull(javaClass.getResourceAsStream("/preferred-table-widths/a4/source.docx")).use { it.readBytes() }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> ZipInputStream(original.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val bytes = input.readBytes()
                val value = when (entry.name) {
                    "word/document.xml" -> bytes.decodeToString().replaceFirst("<w:rPr>",
                        "<w:rPr><w:rStyle w:val=\"InheritedStrike\"/><w:dstrike w:val=\"off\"/><w:w w:val=\"100\"/>").toByteArray()
                    "word/styles.xml", "word/stylesWithEffects.xml" -> bytes.decodeToString().replace("</w:styles>",
                        "<w:style w:type=\"character\" w:styleId=\"InheritedStrike\"><w:name w:val=\"Inherited strike\"/>" +
                            "<w:rPr><w:strike/></w:rPr></w:style>" +
                            "<w:style w:type=\"character\" w:styleId=\"UnusedActive\"><w:name w:val=\"Unused active\"/>" +
                            "<w:rPr><w:dstrike/><w:w w:val=\"105\"/></w:rPr></w:style></w:styles>").toByteArray()
                    else -> bytes
                }
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(value)
                zip.closeEntry()
            }
        } }
        val source = output.toByteArray()
        val before = source.copyOf()
        val document = NativeDocxReader(GenerationContext(), { _, _, _ -> "fixture" }).read(source)
        val first = document.blocks.filterIsInstance<PdfParagraph>().first().runs.filterIsInstance<PdfTextRun>().first()
        assertTrue(first.style.strikethrough)
        assertArrayEquals(before, source)
    }

}

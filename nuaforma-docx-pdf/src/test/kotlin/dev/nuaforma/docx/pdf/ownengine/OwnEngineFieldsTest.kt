package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.*

class OwnEngineFieldsTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun source() = resource("/own-engine-fields/source.docx")
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, name) -> PdfFont(id, resource("/own-engine-fonts/LiberationSans-$name.ttf")) }

    @Test fun `actual page totals replace stale source caches in headers footer tables and all physical ranges`() {
        val model = OwnEngineDocxReader().read(source())
        assertEquals(3, model.pageSequences.size)
        Loader.loadPDF(PdfEngine().render(model, fonts())).use { pdf ->
            assertEquals(13, pdf.numberOfPages)
            for (number in 1..13) {
                val text = PDFTextStripper().apply { startPage = number; endPage = number }.getText(pdf)
                val fields = Regex("Pagina\\s+(\\d+)\\s+van\\s+(\\d+)").findAll(text).map { it.groupValues.drop(1) }.toList()
                assertEquals(listOf(listOf(number.toString(), "13"), listOf(number.toString(), "13")), fields)
                assertFalse(text.contains("999"))
            }
            val text = PDFTextStripper().getText(pdf)
            for (number in 1..140) {
                val id = if (number <= 28) "W${number.toString().padStart(2,'0')}" else "W${number.toString().padStart(3,'0')}"
                assertEquals(if (number <= 28) 3 else 1, Regex("\\b$id\\b").findAll(text).count(), id)
            }
        }
    }

    @Test fun `unsupported total field switches and section index fields reject explicitly`() {
        for (instruction in listOf("NUMPAGES \\* ROMAN", "SECTION", "INCLUDETEXT https://invalid.example/")) {
            assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader().read(change(instruction)) }
        }
    }

    @Test fun `field result cache styling that Word replaces is outside the qualified import profile`() {
        assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader().read(change("NUMPAGES", true)) }
    }

    private fun change(instruction: String, differentSize: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream(); var changed = false
        ZipOutputStream(output).use { target -> ZipInputStream(source().inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break; val bytes = input.readBytes(); target.putNextEntry(ZipEntry(entry.name))
                val value = if (entry.name == "word/footer1.xml") bytes.toString(Charsets.UTF_8).replace(" NUMPAGES ", " $instruction ").let { if (differentSize) it.replace("w:sz w:val=\"18\"", "w:sz w:val=\"20\"") else it }.toByteArray(Charsets.UTF_8) else bytes
                changed = changed || !value.contentEquals(bytes); target.write(value); target.closeEntry()
            }
        } }
        require(changed); return output.toByteArray()
    }
}

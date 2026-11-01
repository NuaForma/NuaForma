package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class OwnEngineParagraphSpacingTest {
    private val cases = listOf("a4-html", "a4-fixed", "letter-html", "letter-fixed", "legacy-html", "legacy-fixed", "a4-fixed-edges", "legacy-fixed-edges")

    private fun source(name: String) = WordLayoutOracle.resource("/paragraph-spacing/$name/source.docx")

    private fun read(name: String) = OwnEngineDocxReader().read(source(name))

    private fun paragraphs(name: String) = read(name).blocks.filterIsInstance<PdfParagraph>().associateBy {
        it.runs.filterIsInstance<PdfTextRun>().joinToString("") { r -> r.text }.substringBefore(' ')
    }

    @Test fun `automatic flags cascade separately from physical gaps and contextual spacing wins`() {
        val html = paragraphs("a4-html")
        for (id in listOf("A00", "S01", "S02", "D01")) {
            assertEquals(14f, html.getValue(id).spaceBefore, id)
            assertEquals(14f, html.getValue(id).spaceAfter, id)
            assertTrue(html.getValue(id).format.collapseSpacing)
        }
        assertEquals(3f, html.getValue("S03").spaceBefore)
        assertEquals(6f, html.getValue("S03").spaceAfter)
        assertEquals(0f, html.getValue("C01").spaceAfter)
        assertEquals(0f, html.getValue("C02").spaceBefore)
        val fixed = paragraphs("a4-fixed")
        assertEquals(40f, fixed.getValue("A01").spaceBefore)
        assertEquals(40f, fixed.getValue("A02").spaceAfter)
        assertEquals(0f, fixed.getValue("S02").spaceBefore)
        assertEquals(5f, fixed.getValue("D01").spaceBefore)
        assertEquals(10f, fixed.getValue("D01").spaceAfter)
        assertTrue(fixed.values.none { it.format.collapseSpacing })
        assertTrue(html.getValue("P00").spacing.suppressBeforeAtForcedPageStart)
        assertFalse(paragraphs("legacy-html").getValue("P00").spacing.suppressBeforeAtForcedPageStart)
    }

    @Test fun `eight independent Word exports qualify page edges inheritance physical sums and table edges`() {
        for (name in cases) WordLayoutOracle.qualify("paragraph-spacing", name, 2, Regex("^([ABSCMDPT][0-9]{2,3})\\b"),
            listOf("A00" to "B00", "B00" to "A01", "A02" to "B02", "S01" to "S02", "S02" to "S03",
                "C01" to "C02", "D01" to "D02", "M01" to "M02", "P01" to "T000", "T000" to "T001",
                "T002" to "T100", "T102" to "P02"))
    }

    @Test fun `invalid flags and unsupported line based gaps fail instead of changing layout`() {
        for (replacement in listOf("w:beforeAutospacing=\"invalid\"", "w:beforeLines=\"100\"", "w:afterLines=\"100\"")) {
            val bytes = ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use { output ->
                ZipInputStream(source("a4-html").inputStream()).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val content = input.readBytes()
                        output.putNextEntry(ZipEntry(entry.name))
                        output.write(if (entry.name == "word/document.xml") content.decodeToString()
                            .replace("w:beforeAutospacing=\"1\"", replacement).toByteArray() else content)
                        output.closeEntry()
                    }
                }
            } }.toByteArray()
            assertThrows(IllegalArgumentException::class.java) { OwnEngineDocxReader().read(bytes) }
        }
    }

}

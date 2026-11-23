package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfParagraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeOutlineResetTest {
    @Test
    fun `a derived body style removes the inherited heading level`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/own-engine-references/a4/source.docx")).use { it.readBytes() }
        val parts = PdfFixture.unzip(bytes).toMutableMap()
        val styles = "word/styles.xml"
        val document = "word/document.xml"
        val previous = parts.getValue(document).decodeToString()
        val changed = previous.replaceFirst("<w:pStyle w:val=\"Heading2\"/>", "<w:pStyle w:val=\"BodyFromHeading\"/>")
        check(previous != changed)

        val declaration = """<w:style w:type="paragraph" w:styleId="BodyFromHeading"><w:name w:val="Body From Heading"/><w:basedOn w:val="Heading2"/><w:pPr><w:outlineLvl w:val="9"/></w:pPr></w:style>"""
        val existingStyles = parts.getValue(styles).decodeToString()
        check("</w:styles>" in existingStyles)
        parts[styles] = existingStyles.replace("</w:styles>", declaration + "</w:styles>").toByteArray()
        parts[document] = changed.toByteArray()

        val reader = OwnEngineDocxReader()
        val original = reader.read(bytes).blocks.filterIsInstance<PdfParagraph>()
        val modified = reader.read(PdfFixture.zip(parts)).blocks.filterIsInstance<PdfParagraph>()
        assertEquals(original.size, modified.size)
        assertEquals(original.count { it.format.outlineLevel == 1 } - 1, modified.count { it.format.outlineLevel == 1 })
        assertEquals(original.count { it.format.outlineLevel == 0 }, modified.count { it.format.outlineLevel == 0 })
    }
}

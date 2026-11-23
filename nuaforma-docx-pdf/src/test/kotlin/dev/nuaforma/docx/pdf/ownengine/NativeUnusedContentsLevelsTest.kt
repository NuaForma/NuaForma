package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReadException
import dev.nuaforma.pdf.PdfParagraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeUnusedContentsLevelsTest {
    @Test
    fun `contents can omit styling only for unused heading levels`() {
        val input = requireNotNull(javaClass.getResourceAsStream("/own-engine-references/a4/source.docx")).use { it.readBytes() }
        val parts = PdfFixture.unzip(input).toMutableMap()
        val wordDocument = "word/document.xml"
        val wordStyles = "word/styles.xml"
        val originalStyles = parts.getValue(wordStyles).decodeToString()
        val levelTwoStyle = Regex("""<w:style w:type="paragraph" w:styleId="TOC2">.*?</w:style>""", RegexOption.DOT_MATCHES_ALL)
        assertEquals(1, levelTwoStyle.findAll(originalStyles).count())
        parts[wordStyles] = originalStyles.replace(levelTwoStyle, "").toByteArray()
        val reader = OwnEngineDocxReader()
        assertThrows(NativeDocxReadException::class.java) { reader.read(PdfFixture.zip(parts)) }

        val original = parts.getValue(wordDocument).decodeToString()
        val secondLevel = Regex("""<w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr>.*?</w:p>""", RegexOption.DOT_MATCHES_ALL)
        assertEquals(2, secondLevel.findAll(original).count())
        parts[wordDocument] = original.replace(secondLevel, "").toByteArray()
        val model = reader.read(PdfFixture.zip(parts))
        val contents = model.blocks.filterIsInstance<PdfParagraph>().single { it.contents != null }.contents!!
        assertEquals(listOf(0), contents.levels.map { it.level })
    }
}

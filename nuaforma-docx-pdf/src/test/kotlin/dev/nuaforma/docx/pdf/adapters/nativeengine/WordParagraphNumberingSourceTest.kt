package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.xml.namespace.QName

class WordParagraphNumberingSourceTest {
    private val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
    private val xml = ConversionXml(context)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun validate(properties: String) {
        val root = xml.read("<w:styles xmlns:w=\"$word\"><w:style w:type=\"paragraph\" w:styleId=\"List\"><w:pPr>$properties</w:pPr></w:style></w:styles>".toByteArray())
        val before = xml.write(root)
        val source = NativeDocxSource(context)
        source.index(root, "word/styles.xml")
        WordNumberingMetadata(context).validateParagraphNumberingSource(root) { node ->
            source.current = node
            source.failure("Unsupported original Word paragraph numbering", "word/styles.xml")
        }
        assertArrayEquals(before, xml.write(root))
    }

    @Test
    fun `independent instance and level inheritance including explicit disabled numbering remain unchanged`() {
        for (children in listOf("<w:numId w:val=\"1\"/>", "<w:ilvl w:val=\"2\"/>",
            "<w:ilvl w:val=\"1\"/><w:numId w:val=\"0\"/>", "<w:numId w:val=\"+000001\"/><w:ilvl w:val=\"000002\"/>"))
            validate("<w:numPr>$children</w:numPr>")
    }

    @Test
    fun `attributes lost by typed binding are identified individually at their original source`() {
        for ((properties, element) in listOf(
            "<w:numPr unexpected=\"1\"><w:numId w:val=\"1\"/></w:numPr>" to "numPr",
            "<w:numPr><w:ilvl w:val=\"1\" unexpected=\"1\"/></w:numPr>" to "ilvl")) {
            val failure = assertThrows(NativeDocxReadException::class.java) { validate(properties) }
            assertEquals("word/styles.xml", failure.part)
            assertTrue(failure.sourceContext.path.orEmpty().endsWith("$element[1]"))
            assertEquals(setOf(QName("unexpected")), failure.unsupportedAttributes)
        }
    }

    @Test
    fun `duplicate nested foreign and malformed numbering values reject before binding`() {
        val cases = mutableListOf("<w:numPr/>", "<w:numPr><w:numId w:val=\"1\"/></w:numPr>".repeat(2),
            "<w:numPr><w:ilvl w:val=\"1\"/><w:ilvl w:val=\"2\"/></w:numPr>",
            "<w:numPr><w:numId w:val=\"1\">{hidden</w:numId></w:numPr>",
            "<w:numPr><w:numId w:val=\"1\"><w:t>{hidden</w:t></w:numId></w:numPr>",
            "<w:numPr><f:numId xmlns:f=\"urn:foreign\" w:val=\"1\"/></w:numPr>",
            "<w:numPr><w:ilvl w:val=\"9\"/></w:numPr>",
            "<w:numPr><w:ilvl w:val=\"-1\"/></w:numPr>")
        for (value in listOf("-1", "1.0", "1e3", "1000001", "١"))
            cases += "<w:numPr><w:numId w:val=\"$value\"/></w:numPr>"
        for (properties in cases) assertThrows(NativeDocxReadException::class.java) { validate(properties) }
    }
}

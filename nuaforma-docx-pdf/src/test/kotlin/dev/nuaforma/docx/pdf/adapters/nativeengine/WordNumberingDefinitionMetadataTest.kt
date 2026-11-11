package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordNumberingDefinitionMetadataTest {
    private val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
    private val xml = ConversionXml(context)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun root(link: String) = xml.read("<w:numbering xmlns:w=\"$word\"><w:abstractNum w:abstractNumId=\"0\">$link<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/></w:lvl></w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>".toByteArray())

    private fun normalize(root: XmlElement) {
        val source = NativeDocxSource(context)
        source.index(root, "word/numbering.xml")
        WordNumberingMetadata(context).normalize(root) { node ->
            source.current = node
            source.failure("Unsupported Word numbering metadata", "word/numbering.xml")
        }
    }

    @Test
    fun `definition names remain inert and preserve the concrete numbering rules`() {
        for (name in listOf("KnownList", "Missing {literal", "A".repeat(253))) {
            val root = root("<w:styleLink w:val=\"$name\"/>")
            normalize(root)
            assertTrue(root.descendants().none { it.name.localPart == "styleLink" })
            val body = xml.read("<w:body xmlns:w=\"$word\"><w:p><w:pPr><w:numPr><w:numId w:val=\"1\"/></w:numPr></w:pPr></w:p></w:body>".toByteArray())
            val entry = NativeDocxNumbering(root, body) { emptyList() }.entry(body.elements().single())!!
            assertEquals("1.", entry.value.text)
            assertEquals(0, entry.value.level)
        }
    }

    @Test
    fun `duplicate foreign empty oversized and nested definition links fail at their source`() {
        val cases = listOf("<w:styleLink/>", "<w:styleLink w:val=\"\"/>",
            "<w:styleLink w:val=\"${"A".repeat(254)}\"/>",
            "<w:styleLink w:val=\"List\" unknown=\"1\"/>",
            "<w:styleLink w:val=\"List\"><w:t>{hidden</w:t></w:styleLink>",
            "<f:styleLink xmlns:f=\"urn:foreign\" w:val=\"List\"/>",
            "<w:styleLink w:val=\"List\"/><w:styleLink w:val=\"Other\"/>")
        for (link in cases) {
            val failure = assertThrows(NativeDocxReadException::class.java) { normalize(root(link)) }
            assertEquals("word/numbering.xml", failure.part)
            assertTrue(failure.sourceContext.path.orEmpty().contains("styleLink"), link)
        }
    }

    @Test
    fun `active numbering-style redirection is retained and rejected at the actual element`() {
        val root = root("<w:numStyleLink w:val=\"List\"/>")
        normalize(root)
        assertTrue(root.descendants().any { it.name.localPart == "numStyleLink" })
        val body = xml.read("<w:body xmlns:w=\"$word\"/>".toByteArray())
        var focused: XmlElement? = null
        assertThrows(IllegalArgumentException::class.java) {
            NativeDocxNumbering(root, body, focus = { focused = it }) { emptyList() }
        }
        assertEquals("numStyleLink", focused?.name?.localPart)
    }
}

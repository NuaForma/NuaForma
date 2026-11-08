package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordNumberingMetadataTest {
    private val context = GenerationContext()
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val word15 = "http://schemas.microsoft.com/office/word/2012/wordml"
    private val durable = "http://schemas.microsoft.com/office/word/2016/wordml/cid"

    private fun xml(content: String) = ConversionXml(context).read("""<w:numbering xmlns:w="$word" xmlns:w15="$word15" xmlns:w16cid="$durable">$content</w:numbering>""".toByteArray())

    private fun normalize(content: String) = xml(content).also { root -> WordNumberingMetadata(context).normalize(root) {
        NativeDocxReadException("Unsupported Word numbering metadata", "word/numbering.xml")
    } }

    @Test fun `gallery metadata and disabled section restarts retain printed list values`() {
        val source = """<w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl></w:abstractNum><w:num w:numId="7"><w:abstractNumId w:val="0"/></w:num>"""
        val decorated = source.replace("w:abstractNumId=\"0\"", "w:abstractNumId=\"0\" w15:restartNumberingAfterBreak=\"0\"")
            .replace("w:ilvl=\"0\"", "w:ilvl=\"0\" w:tplc=\"04090001\" w:tentative=\"1\"")
            .replace("w:numId=\"7\"", "w:numId=\"7\" w16cid:durableId=\"123456789\"")
        val body = ConversionXml(context).read(("""<w:body xmlns:w="$word">""" +
            """<w:p><w:pPr><w:numPr><w:numId w:val="7"/></w:numPr></w:pPr></w:p>""".repeat(3) + "</w:body>").toByteArray())
        val expected = NativeDocxNumbering(xml(source), body) { emptyList() }
        val actual = NativeDocxNumbering(normalize(decorated + "<w:numIdMacAtCleanup w:val=\"2147483647\"/>"), body) { emptyList() }
        for (paragraph in body.elements()) assertEquals(expected.entry(paragraph)?.value, actual.entry(paragraph)?.value)
        assertNotNull(actual.entry(body.elements().first()))
        assertTrue(normalize("").elements().isEmpty())
    }

    @Test
    fun `cleanup bookkeeping never prunes actual numbering definitions or changes counters`() {
        for (value in listOf("0", "4", "2147483647", "+42")) {
            val root = normalize("<w:abstractNum w:abstractNumId=\"4\"/><w:numIdMacAtCleanup w:val=\"$value\"/>")
            assertEquals(listOf("abstractNum"), root.elements().map { it.name.localPart })
        }
        for (bad in listOf("<w:numIdMacAtCleanup/>", "<w:numIdMacAtCleanup val=\"4\"/>",
            "<w:numIdMacAtCleanup w:val=\"-1\"/>", "<w:numIdMacAtCleanup w:val=\"2147483648\"/>",
            "<w:numIdMacAtCleanup w:val=\"4\">text</w:numIdMacAtCleanup>",
            "<w:numIdMacAtCleanup w:val=\"4\"><w:num/></w:numIdMacAtCleanup>",
            "<w:numIdMacAtCleanup w:val=\"4\"/><w:numIdMacAtCleanup w:val=\"4\"/>")) {
            assertThrows(NativeDocxReadException::class.java, { normalize(bad) }, bad)
        }
    }

    @Test fun `malformed gallery identifiers and effective section restarts remain explicit errors`() {
        for (content in listOf("<w:lvl w:tplc=\"bad\"/>", "<w:lvl w:tentative=\"invalid\"/>",
            "<w:num w16cid:durableId=\"0\"/>", "<w:num w16cid:durableId=\"2147483647\"/>", "<w:num w16cid:durableId=\"ABCDEF01\"/>",
            "<w:abstractNum w15:restartNumberingAfterBreak=\"1\"/>")) {
            val error = assertThrows(NativeDocxReadException::class.java) { normalize(content) }
            assertEquals("word/numbering.xml", error.part)
        }
    }

    @Test fun `foreign attributes or misplaced authoring attributes are preserved for strict downstream rejection`() {
        val root = normalize("""<w:num w:tplc="04090001"/><foreign xmlns="urn:unknown" w:tentative="1"/>""")
        assertTrue(root.elements().all { it.attributes.isNotEmpty() })
    }

}

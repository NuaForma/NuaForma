package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordMarkupCompatibilityTest {
    private val mc = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    private val wps = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"

    private fun root(content: String): XmlElement = ConversionXml(GenerationContext()).read(
        """<w:document xmlns:w="${PdfFixture.W}" xmlns:mc="$mc" xmlns:shape="$wps" xmlns:u="urn:future"><w:body><w:p><w:r>$content</w:r></w:p></w:body></w:document>""".toByteArray())

    private fun normalize(root: XmlElement): Boolean = WordMarkupCompatibility(GenerationContext()).normalize(root) { _, message -> NativeDocxReadException(message, "word/document.xml") }

    private fun text(root: XmlElement): String = root.descendants().filter { it.name.namespaceURI == PdfFixture.W && it.name.localPart == "t" }
        .flatMap { it.children.asSequence().filterIsInstance<XmlText>() }.joinToString("") { it.text }

    @Test
    fun `choose first understood URI with arbitrary prefixes and exclude all other text`() {
        val root = root("""<mc:AlternateContent><mc:Choice Requires="u"><w:t>{bad</w:t></mc:Choice><mc:Choice Requires="shape"><w:t>selected</w:t></mc:Choice><mc:Choice Requires="shape"><w:t>later</w:t></mc:Choice><mc:Fallback><w:t>fallback</w:t></mc:Fallback></mc:AlternateContent>""")
        assertTrue(normalize(root)); assertEquals("selected", text(root)); assertFalse(normalize(root))
        assertTrue(root.descendants().none { it.name.namespaceURI == mc })
    }

    @Test
    fun `fallback selection respects shadowed prefix bindings`() {
        val root = root("""<mc:AlternateContent><mc:Choice xmlns:shape="urn:future" Requires="shape"><w:t>hidden</w:t></mc:Choice><mc:Fallback><w:t>fallback</w:t></mc:Fallback></mc:AlternateContent>""")
        normalize(root); assertEquals("fallback", text(root))
    }

    @Test
    fun `nested selections inherit local bindings after serialization and accept Unicode prefixes`() {
        val root = root("""<mc:AlternateContent xmlns:π="$wps"><mc:Choice Requires="π"><w:t>outer</w:t><mc:AlternateContent><mc:Choice Requires="π"><w:t>inner</w:t></mc:Choice></mc:AlternateContent></mc:Choice></mc:AlternateContent>""")
        normalize(root)
        val xml = ConversionXml(GenerationContext())
        val restored = xml.read(xml.write(root))
        assertEquals("outerinner", text(restored))
        assertTrue(restored.descendants().any { it.namespaces["π"] == wps })
    }

    @Test
    fun `malformed containers branches bindings and policies remain errors`() {
        for (bad in listOf("", "<mc:Fallback/>", "<mc:Choice/>", "<mc:Choice Requires=\"\"/>",
            "<mc:Choice Requires=\"missing\"/>", "<mc:Choice Requires=\"u\"/>",
            "<mc:Choice Requires=\"shape\"/><mc:Fallback/><mc:Fallback/>", "<mc:Choice Requires=\"shape\"/><mc:Fallback/><mc:Choice Requires=\"shape\"/>",
            "<mc:Choice w:Requires=\"shape\"/>", "<mc:Choice Requires=\"shape\"/><mc:Fallback extra=\"1\"/>",
            "<w:r/>", "text<mc:Choice Requires=\"shape\"/>", "<mc:Choice Requires=\"shape\">text</mc:Choice>"))
            assertThrows(NativeDocxReadException::class.java, { normalize(root("<mc:AlternateContent>$bad</mc:AlternateContent>")) }, bad)
        assertThrows(NativeDocxReadException::class.java) { normalize(root("<mc:Choice Requires=\"shape\"/>")) }
        assertThrows(NativeDocxReadException::class.java) { normalize(root("<mc:AlternateContent mc:MustUnderstand=\"u\"><mc:Choice Requires=\"shape\"/></mc:AlternateContent>")) }
    }

    @Test
    fun `an explicitly empty selected choice retains no hidden fallback text`() {
        val root = root("<mc:AlternateContent><mc:Choice Requires=\"shape\"/><mc:Fallback><w:t>{bad</w:t></mc:Fallback></mc:AlternateContent>")
        normalize(root); assertEquals("", text(root))
    }

    @Test
    fun `unchanged source remains byte-identical and package budgets still apply`() {
        val original = PdfFixture.zip(PdfFixture.parts())
        assertSame(original, WordMarkupCompatibility.prepare(original, GenerationContext()))
        val error = assertThrows(GenerationException::class.java) {
            WordMarkupCompatibility.prepare(original, GenerationContext(GenerationLimits(maxArchiveEntries = 1)))
        }
        assertTrue(error.diagnostics.any { it.code == DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED })
        val malformed = byteArrayOf(1, 2, 3)
        assertSame(malformed, WordMarkupCompatibility.prepare(malformed, GenerationContext()))
        var nested = "<w:t>nested</w:t>"
        repeat(64) { nested = "<mc:AlternateContent><mc:Choice Requires=\"shape\">$nested</mc:Choice></mc:AlternateContent>" }
        val parts = PdfFixture.parts()
        parts["word/document.xml"] = """<w:document xmlns:w="${PdfFixture.W}" xmlns:mc="$mc" xmlns:shape="$wps"><w:body><w:p><w:r>$nested</w:r></w:p></w:body></w:document>""".toByteArray()
        val depth = assertThrows(GenerationException::class.java) { WordMarkupCompatibility.prepare(PdfFixture.zip(parts), GenerationContext()) }
        assertTrue(depth.diagnostics.any { it.code == DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED })
    }

}

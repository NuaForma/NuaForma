package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordWebSettingsTest {
    private val context = GenerationContext()
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private fun normalize(content: String, attribute: String = "") = ConversionXml(context).read(
        """<w:webSettings xmlns:w="$word" $attribute>$content</w:webSettings>""".toByteArray()).also { root ->
        WordWebSettings(context).normalize(root) { NativeDocxReadException("Unsupported Word web settings", "word/webSettings.xml") }
    }

    @Test fun `HTML round trip margins borders and browser preferences stay outside the print model`() {
        val root = normalize("""<w:divs><w:div w:id="1"><w:bodyDiv/><w:marTop w:val="0"/><w:marBottom w:val="0"/>
            <w:marLeft w:val="720"/><w:marRight w:val="720"/><w:divBdr><w:top w:val="single"/><w:bottom w:val="nil"/>
            <w:left w:val="single" w:sz="6" w:color="auto"/><w:right w:val="single"/></w:divBdr>
            <w:divsChild><w:div w:id="2"><w:blockQuote/></w:div></w:divsChild></w:div></w:divs>
            <w:optimizeForBrowser w:target="W3C HTML4+CSS2"/><w:allowPNG w:val="true"/>""")
        assertTrue(root.children.isEmpty())
        assertTrue(normalize("").children.isEmpty())
    }

    @Test fun `foreign active nested unknown or textual web settings reject precisely`() {
        for (content in listOf("<w:unknown/>", "<w:allowPNG>private metadata</w:allowPNG>",
            "<w:allowPNG><w:divs/></w:allowPNG>", "<w:divs><w:div/></w:divs>",
            "<w:divs><w:div w:id=\"invalid\"/></w:divs>", "<w:divs><w:div w:id=\"1\"><w:p/></w:div></w:divs>",
            "<w:allowPNG xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:id=\"remote\"/>",
            "<w:allowPNG unexpected=\"1\"/>", "<allowPNG xmlns=\"https://invalid.example/\"/>")) {
            val error = assertThrows(NativeDocxReadException::class.java) { normalize(content) }
            assertEquals("word/webSettings.xml", error.part)
            assertEquals("", error.sourceContext.excerpt)
        }
        assertThrows(NativeDocxReadException::class.java) { normalize("", "unexpected=\"1\"") }
        assertThrows(NativeDocxReadException::class.java) {
            WordWebSettings(context).normalize(ConversionXml(context).read("<Wrong/>".toByteArray())) {
                NativeDocxReadException("Unsupported Word web settings", "word/webSettings.xml")
            }
        }
    }

    @Test fun `passive markup compatibility declarations are normalized before HTML metadata`() {
        val root = ConversionXml(context).read("""<w:webSettings xmlns:w="$word" xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" mc:Ignorable="w"><w:optimizeForBrowser/></w:webSettings>""".toByteArray())
        WordAuthoringMetadata.normalize(root, "word/webSettings.xml", false)
        WordWebSettings(context).normalize(root) { error("Known web metadata") }
        assertTrue(root.children.isEmpty())
        for (content in listOf("<w:allowPNG w:val=\"invalid\"/>", "<w:divs><w:div w:id=\"1\"><w:marTop w:val=\"invalid\"/></w:div></w:divs>"))
            assertThrows(NativeDocxReadException::class.java) { normalize(content) }
    }
}

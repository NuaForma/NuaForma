package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordProofingDisplayTest {
    private val context = GenerationContext()
    private val xml = ConversionXml(context)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun settings(content: String) = xml.read(("<w:settings xmlns:w=\"$word\" xmlns:x=\"urn:unqualified\">" + content + "</w:settings>").toByteArray())

    @Test
    fun `proofing display preferences accept every Word boolean without mutating source`() {
        for (name in listOf("hideSpellingErrors", "hideGrammaticalErrors")) {
            for (value in listOf(null, "0", "1", "false", "true", "off", "on")) {
                val node = settings("<w:$name" + (value?.let { " w:val=\"$it\"" } ?: "") + "/>")
                val before = xml.write(node)
                WordSettingsMetadata(context).validateEditingSource(node) { error("Valid preference rejected") }
                assertArrayEquals(before, xml.write(node))
            }
        }
    }

    @Test
    fun `invalid original proofing preferences cannot disappear in the DOCX binder`() {
        for (name in listOf("hideSpellingErrors", "hideGrammaticalErrors")) {
            for (content in listOf("<w:$name w:val=\"unknown\"/>", "<w:$name extra=\"1\"/>",
                "<w:$name/><w:$name/>", "<x:$name/>", "<w:$name val=\"0\"/>",
                "<w:$name><w:b/></w:$name>", "<w:$name>text</w:$name>")) {
                assertThrows(IllegalArgumentException::class.java) {
                    WordSettingsMetadata(context).validateEditingSource(settings(content)) { throw IllegalArgumentException(it.name.localPart) }
                }
            }
        }
    }

    @Test
    fun `normalization removes only editor proofing preferences and retains printable structure`() {
        val root = settings("<w:defaultTabStop w:val=\"720\"/><w:hideSpellingErrors/><w:hideGrammaticalErrors w:val=\"false\"/>")
        val document = xml.read("<w:document xmlns:w=\"$word\"><w:body><w:p><w:proofErr w:type=\"spellStart\"/><w:r><w:t>Visible text</w:t></w:r><w:proofErr w:type=\"spellEnd\"/></w:p></w:body></w:document>".toByteArray())
        val before = xml.write(document)
        val formatting = WordSettingsMetadata(context).normalize(mapOf("word/settings.xml" to root, "word/document.xml" to document)) { part, _ ->
            NativeDocxReadException("Unexpected proofing-display failure", part)
        }
        assertEquals(WordSettingsMetadata.Formatting(), formatting)
        assertEquals(listOf("defaultTabStop"), root.elements().map { it.name.localPart })
        assertArrayEquals(before, xml.write(document))
    }
}

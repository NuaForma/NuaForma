package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordEditingPreferencesTest {
    private val context = GenerationContext()
    private val xml = ConversionXml(context)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun settings(content: String) = xml.read(("<w:settings xmlns:w=\"$word\" xmlns:x=\"urn:unqualified\">" + content + "</w:settings>").toByteArray())

    @Test
    fun `original editing booleans are validated without changing qualified source`() {
        for (name in listOf("doNotAutoCompressPictures", "trackRevisions")) for (value in listOf(null, "0", "1", "false", "true", "off", "on")) {
            val root = settings("<w:$name" + (value?.let { " w:val=\"$it\"" } ?: "") + "/>")
            val before = xml.write(root)
            WordSettingsMetadata(context).validateEditingSource(root) { error("Valid preference rejected") }
            assertArrayEquals(before, xml.write(root))
        }
    }

    @Test
    fun `malformed and duplicate original preferences cannot disappear during DOCX serialization`() {
        for (name in listOf("doNotAutoCompressPictures", "trackRevisions", "removePersonalInformation")) {
            for (content in listOf("<w:$name w:val=\"unknown\"/>", "<w:$name extra=\"1\"/>",
                "<w:$name/><w:$name/>", "<x:$name/>", "<w:$name val=\"0\"/>", "<w:$name><w:b/></w:$name>")) {
                assertThrows(IllegalArgumentException::class.java) {
                    WordSettingsMetadata(context).validateEditingSource(settings(content)) { throw IllegalArgumentException(it.name.localPart) }
                }
            }
        }
    }

    @Test
    fun `private normalization removes preferences while preserving unrelated settings and revision containers`() {
        val root = settings("<w:defaultTabStop w:val=\"720\"/><w:doNotAutoCompressPictures/><w:trackRevisions w:val=\"false\"/>")
        val document = xml.read("<w:document xmlns:w=\"$word\"><w:body><w:p><w:ins w:id=\"1\" w:author=\"Fixture\"><w:r><w:t>Revision content</w:t></w:r></w:ins></w:p></w:body></w:document>".toByteArray())
        val original = xml.write(document)
        val result = WordSettingsMetadata(context).normalize(mapOf("word/settings.xml" to root, "word/document.xml" to document)) { part, _ ->
            NativeDocxReadException("Unexpected preference failure", part)
        }
        assertEquals(WordSettingsMetadata.Formatting(), result)
        assertEquals(listOf("defaultTabStop"), root.elements().map { it.name.localPart })
        assertArrayEquals(original, xml.write(document))
    }
}

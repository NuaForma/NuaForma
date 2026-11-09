package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordDocumentVariablesTest {
    private val context = GenerationContext()
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun settings(content: String) = ConversionXml(context).read(("<w:settings xmlns:w=\"$word\" xmlns:x=\"urn:unqualified\">" + content + "</w:settings>").toByteArray())

    private fun variable(name: String, value: String) = "<w:docVar w:name=\"$name\" w:val=\"$value\"/>"

    @Test
    fun `unused storage preserves names and values without changing source or visible settings`() {
        for (content in listOf("<w:docVars/>", "<w:docVars>" + variable("Stored", "{NOT_A_TEMPLATE العربية Русский 中文 _x0041_") + variable("Empty", "") + "</w:docVars>")) {
            val root = settings("<w:defaultTabStop w:val=\"720\"/>" + content)
            val xml = ConversionXml(context)
            val before = xml.write(root)
            WordSettingsMetadata(context).validateDocumentVariablesSource(root) { error("Qualified storage rejected") }
            assertArrayEquals(before, xml.write(root))
            assertEquals(WordSettingsMetadata.Formatting(), WordSettingsMetadata(context).normalize(mapOf("word/settings.xml" to root)) { part, _ -> NativeDocxReadException("Unexpected storage failure", part) })
            assertEquals(listOf("defaultTabStop"), root.elements().map { it.name.localPart })
        }
    }

    @Test
    fun `malformed original variable storage cannot disappear during serialization`() {
        for (content in listOf("<w:docVars/><w:docVars/>", "<x:docVars/>", "<w:docVars extra=\"1\"/>",
            "<w:docVars>text</w:docVars>", "<w:docVars><w:docVar w:name=\"Missing value\"/></w:docVars>",
            "<w:docVars><w:docVar w:val=\"Missing name\"/></w:docVars>", "<w:docVars><x:docVar/></w:docVars>",
            "<w:docVars><w:docVar w:name=\"Leaf\" w:val=\"value\"><w:b/></w:docVar></w:docVars>",
            "<w:docVars><w:docVar w:name=\"Leaf\" w:val=\"value\" extra=\"1\"/></w:docVars>",
            "<w:docVars>" + variable("A", "one") + variable("a", "two") + "</w:docVars>",
            "<w:docVars>" + variable(" ", "one") + "</w:docVars>")) {
            val root = settings(content)
            val xml = ConversionXml(context)
            val before = xml.write(root)
            assertThrows(IllegalArgumentException::class.java) {
                WordSettingsMetadata(context).validateDocumentVariablesSource(root) { throw IllegalArgumentException(it.name.localPart) }
            }
            assertArrayEquals(before, xml.write(root))
        }
    }

    @Test
    fun `storage stays within per-name per-value count and aggregate bounds`() {
        val supported = listOf(variable("N".repeat(255), "V".repeat(16_384)), (1..1_000).joinToString("") { variable("N$it", "") })
        for (content in supported) WordSettingsMetadata(context).validateDocumentVariablesSource(settings("<w:docVars>$content</w:docVars>")) { error("Boundary rejected") }
        for (content in listOf(variable("N".repeat(256), "V"), variable("Name", "V".repeat(16_385)),
            (1..1_001).joinToString("") { variable("N$it", "") }, (1..4).joinToString("") { variable("N$it", "V".repeat(16_384)) })) {
            assertThrows(IllegalArgumentException::class.java) {
                WordSettingsMetadata(context).validateDocumentVariablesSource(settings("<w:docVars>$content</w:docVars>")) { throw IllegalArgumentException(it.name.localPart) }
            }
        }
    }

    @Test
    fun `variable scanning cooperates with request cancellation`() {
        val cancelled = GenerationContext(cancellation = CancellationToken { true })
        assertThrows(GenerationException::class.java) {
            WordSettingsMetadata(cancelled).validateDocumentVariablesSource(settings("<w:docVars/>")) { error("Unexpected shape failure") }
        }
    }
}

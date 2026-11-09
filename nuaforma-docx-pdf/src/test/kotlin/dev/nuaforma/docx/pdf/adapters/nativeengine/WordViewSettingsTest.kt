package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordViewSettingsTest {
    private val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
    private val xml = ConversionXml(context)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val part = "word/settings.xml"

    private fun settings(body: String): XmlElement = xml.read("<w:settings xmlns:w=\"$word\">$body</w:settings>".toByteArray())

    private fun validate(root: XmlElement) {
        val source = NativeDocxSource(context)
        source.index(root, part)
        val metadata = WordSettingsMetadata(context)

        fun reject(node: XmlElement): Nothing {
            source.current = node
            throw source.failure("Unsupported Word view or read-only setting", part)
        }

        metadata.validateEditingSource(root, ::reject)
        metadata.validateReadOnlySource(root, ::reject)
    }

    @Test
    fun `qualified on-off spellings and recommendation leaves never change print formatting`() {
        for (value in listOf(null, "0", "1", "true", "false", "on", "off")) {
            val attribute = value?.let { " w:val=\"$it\"" }.orEmpty()
            val recommendation = value?.let { " w:recommended=\"$it\"" }.orEmpty()
            val root = settings("<w:displayBackgroundShape$attribute/><w:doNotShadeFormData$attribute/><w:writeProtection$recommendation/>")
            validate(root)
            assertEquals(WordSettingsMetadata.Formatting(), WordSettingsMetadata(context).normalize(mapOf(part to root)) { name, _ ->
                NativeDocxReadException("Unsupported Word settings", name)
            })
            assertTrue(root.elements().isEmpty())
        }
    }

    @Test
    fun `invalid recommendation payload identities and duplicate properties retain source location`() {
        for (body in listOf(
            "<w:writeProtection w:recommended=\"yes\"/>",
            "<w:writeProtection recommended=\"1\"/>",
            "<writeProtection xmlns=\"urn:foreign\"/>",
            "<w:writeProtection w:recommended=\"1\"><w:unknown/></w:writeProtection>",
            "<w:writeProtection w:cryptAlgorithmSid=\"14\"/>",
            "<w:writeProtection/><w:writeProtection/>",
            "<w:displayBackgroundShape w:val=\"yes\"/>",
            "<w:displayBackgroundShape/><w:displayBackgroundShape/>",
            "<w:doNotShadeFormData foreign=\"x\"/>",
            "<w:doNotShadeFormData><w:unknown/></w:doNotShadeFormData>")) {
            val failure = assertThrows(NativeDocxReadException::class.java) { validate(settings(body)) }
            assertEquals(part, failure.part)
            assertTrue(failure.sourceContext.path.orEmpty().startsWith("/w:settings[1]/"))
        }
    }

}

package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordAuthoringMetadataTest {
    private fun paragraph(content: String) = ConversionXml(GenerationContext()).read(
        ("<w:p xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" + content + "</w:p>").toByteArray())

    @Test fun `qualified proofing markers are passive while visible text remains unchanged`() {
        val root = paragraph("<w:r><w:t>Before</w:t></w:r>" +
            listOf("spellStart", "spellEnd", "gramStart", "gramEnd").joinToString("") { "<w:proofErr w:type=\"$it\"/>" } +
            "<w:r><w:t>After</w:t></w:r>")
        WordAuthoringMetadata.normalize(root, "word/document.xml", false)
        assertEquals(listOf("r", "r"), root.elements().map { it.name.localPart })
    }

    @Test fun `proofing markers cannot disguise unsupported attributes or child content`() {
        for (xml in listOf("<w:proofErr/>", "<w:proofErr w:type=\"other\"/>",
            "<w:proofErr w:type=\"spellStart\" w:other=\"1\"/>",
            "<w:proofErr w:type=\"spellStart\"><w:r><w:t>Hidden content</w:t></w:r></w:proofErr>")) {
            assertThrows(NativeDocxReadException::class.java) {
                WordAuthoringMetadata.normalize(paragraph(xml), "word/document.xml", false)
            }
        }
    }
}

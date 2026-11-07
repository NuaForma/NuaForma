package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordSensitivityMetadataTest {
    private val context = GenerationContext()
    private val part = "docMetadata/LabelInfo.xml"
    private val ns = "http://schemas.microsoft.com/office/2020/mipLabelMetadata"
    private val relation = "http://schemas.microsoft.com/office/2020/02/relationships/classificationlabels"
    private val label = """<label id="{11111111-1111-1111-1111-111111111111}" siteId="{22222222-2222-2222-2222-222222222222}" enabled="1" removed="0" method="Standard" contentBits="2"/>"""

    private fun xml(text: String) = ConversionXml(context).read(text.toByteArray())

    private fun parts(content: String = label): MutableMap<String, XmlElement> = linkedMapOf(
        part to xml("""<labelList xmlns="$ns">$content</labelList>"""),
        "_rels/.rels" to xml("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="label" Type="$relation" Target="$part"/></Relationships>"""),
        "[Content_Types].xml" to xml("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/$part" ContentType="application/vnd.ms-office.classificationlabels+xml"/></Types>"""))

    private fun normalize(parts: MutableMap<String, XmlElement>) = WordSensitivityMetadata(context).removeFromPrintModel(parts)

    private fun rejects(parts: MutableMap<String, XmlElement>) {
        val error = assertThrows(NativeDocxReadException::class.java) { normalize(parts) }
        assertEquals(part, error.part)
        assertEquals("Unsupported or invalid sensitivity-label metadata", error.sourceContext.explanation)
    }

    @Test
    fun `known label metadata is removed only from the in-memory print model`() {
        for (bits in 0..7) {
            val parts = parts(label.replace("contentBits=\"2\"", "contentBits=\"$bits\""))
            val types = parts.getValue("[Content_Types].xml")
            val visible = xml("""<w:p xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:r><w:t>Existing marking</w:t></w:r></w:p>""")
            parts["word/footer1.xml"] = visible
            normalize(parts)
            assertFalse(part in parts)
            assertTrue(parts.getValue("_rels/.rels").elements().isEmpty())
            assertSame(types, parts["[Content_Types].xml"])
            assertSame(visible, parts["word/footer1.xml"])
        }
        normalize(parts(""))
        normalize(linkedMapOf())
        normalize(parts(label.replace("enabled=\"1\"", "enabled=\"false\"").replace("removed=\"0\"", "removed=\"true\"").replace("method=\"Standard\"", "method=\"\"")))
    }

    @Test
    fun `unknown content active policies duplicate tenants and malformed scalar values reject`() {
        for (invalid in listOf(label + label, label.repeat(65), label.replace("contentBits=\"2\"", "contentBits=\"8\""),
            label.replace("contentBits=\"2\"", "contentBits=\"4294967296\""), label.replace("contentBits=\"2\"", "contentBits=\"-1\""),
            label.replace("enabled=\"1\"", "enabled=\"yes\""), label.replace("method=\"Standard\"", "method=\"Unknown\""),
            label.replace("removed=\"0\"", "removed=\"1\""), label.replace("22222222-2222-2222-2222-222222222222", "not-a-guid"),
            label.replace("/>", " foreign=\"x\"/>"), label.replace("/>", "><foreign/></label>"), "{neverEvaluate", "<foreign/>")) rejects(parts(invalid))
        rejects(parts().also { it[part] = xml("<labelList/>") })
        rejects(parts().also { it[part] = xml("""<labelList xmlns="$ns" extra="1"/>""") })
    }

    @Test
    fun `label relationship must be singular local exact and paired with the part`() {
        for (content in listOf(
            """<Relationship Id="label" Type="$relation" Target="https://invalid.example/label" TargetMode="External"/>""",
            """<Relationship Id="label" Type="$relation" Target="$part" TargetMode="External"/>""",
            """<Relationship Id="label" Type="$relation" Target="../$part"/>""",
            """<Relationship Id="label" Type="$relation" Target="$part" extra="x"/>""", "")) {
            rejects(parts().also { it["_rels/.rels"] = xml("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$content</Relationships>""") })
        }
        rejects(parts().also { it.remove(part) })
        rejects(parts().also { it.remove("_rels/.rels") })
    }

    @Test
    fun `label content type and cancellation remain enforced`() {
        rejects(parts().also { it.remove("[Content_Types].xml") })
        rejects(parts().also { it["[Content_Types].xml"] = xml("<Types/>") })
        val cancelled = GenerationContext(cancellation = CancellationToken { true })
        val error = assertThrows(GenerationException::class.java) { WordSensitivityMetadata(cancelled).removeFromPrintModel(parts()) }
        assertEquals(DiagnosticCodes.CANCELLED, error.diagnostics.single().code)
    }
}

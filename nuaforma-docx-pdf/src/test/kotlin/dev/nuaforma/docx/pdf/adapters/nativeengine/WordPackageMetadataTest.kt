package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordPackageMetadataTest {
    private val context = GenerationContext()
    private val rel = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val custom = "http://schemas.openxmlformats.org/officeDocument/2006/custom-properties"
    private val vt = "http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"
    private val properties = "docProps/custom.xml"
    private fun xml(text: String) = ConversionXml(context).read(text.toByteArray())
    private fun property(value: String = "<vt:lpwstr>{neverEvaluate</vt:lpwstr>", pid: String = "2", name: String = "Example", extra: String = "") =
        """<property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="$pid" name="$name" $extra>$value</property>"""
    private fun parts(content: String = property(), target: String = properties, mode: String = "", link: String? = null): MutableMap<String, XmlElement> = linkedMapOf(
        properties to xml("""<Properties xmlns="$custom" xmlns:vt="$vt">$content</Properties>"""),
        "_rels/.rels" to xml("""<Relationships xmlns="$rel">${link ?: """<Relationship Id="custom" Type="$office/custom-properties" Target="$target" $mode/>"""}</Relationships>"""),
        "[Content_Types].xml" to xml("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/$properties" ContentType="application/vnd.openxmlformats-officedocument.custom-properties+xml"/></Types>"""))
    private fun normalize(parts: MutableMap<String, XmlElement>) = WordPackageMetadata(context).removeFromPrintModel(parts)
    private fun rejects(parts: MutableMap<String, XmlElement>, part: String) {
        val error = assertThrows(NativeDocxReadException::class.java) { normalize(parts) }
        assertEquals(part, error.part)
        assertFalse(error.sourceContext.excerpt.contains("neverEvaluate"))
    }

    @Test fun `passive scalar metadata is excluded without inspecting braces or RTL text as document content`() {
        for ((type, text) in listOf("lpwstr" to "{neverEvaluate العربية", "lpstr" to "}", "bstr" to "{", "bool" to "true",
            "i1" to "-1", "ui8" to "18446744073709551615", "r4" to "1.25", "r8" to "1e5", "decimal" to "2.50",
            "filetime" to "2026-11-01T00:00:00Z", "date" to "2026-11-01T01:00:00+01:00")) {
            val parts = parts(property("<vt:$type>$text</vt:$type>"), mode = "TargetMode=\"Internal\"")
            normalize(parts)
            assertFalse(properties in parts)
            assertTrue(parts.getValue("_rels/.rels").elements().isEmpty())
        }
        normalize(parts(""))
        normalize(linkedMapOf())
        normalize(linkedMapOf("_rels/.rels" to xml("""<Relationships xmlns="$rel"/>""")))
    }

    @Test fun `detached settings relationships accept only empty package relationship roots`() {
        val settings = xml("""<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:defaultTabStop w:val="720"/></w:settings>""")
        val parts = linkedMapOf("word/settings.xml" to settings, "word/_rels/settings.xml.rels" to xml("""<Relationships xmlns="$rel"> </Relationships>"""))
        normalize(parts)
        assertSame(settings, parts["word/settings.xml"])
        assertEquals("720", settings.elements().single().attribute(settings.name.namespaceURI, "val"))
        assertFalse("word/_rels/settings.xml.rels" in parts)
        for (root in listOf("""<Relationships xmlns="$rel"><Relationship Id="bad" Type="$office/attachedTemplate" Target="https://invalid.example/" TargetMode="External"/></Relationships>""",
            """<Relationships xmlns="$rel" unexpected="1"/>""", "<Relationships/>", """<Relationships xmlns="$rel">unexpected</Relationships>""")) {
            rejects(linkedMapOf("word/settings.xml" to settings, "word/_rels/settings.xml.rels" to xml(root)), "word/_rels/settings.xml.rels")
        }
        rejects(linkedMapOf("word/_rels/settings.xml.rels" to xml("""<Relationships xmlns="$rel"/>""")), "word/_rels/settings.xml.rels")
    }

    @Test fun `custom property links reject external targets orphans duplicates and malformed identifiers`() {
        rejects(parts(target = "https://invalid.example/custom.xml", mode = "TargetMode=\"External\""), "_rels/.rels")
        rejects(parts(mode = "TargetMode=\"External\""), "_rels/.rels")
        rejects(parts(link = ""), "_rels/.rels")
        rejects(parts().also { it.remove(properties) }, properties)
        val link = """<Relationship Id="custom" Type="$office/custom-properties" Target="$properties"/>"""
        rejects(parts(link = link + link), "_rels/.rels")
        rejects(parts(link = link.replace("Id=\"custom\"", "Id=\"\"")), "_rels/.rels")
        rejects(parts(link = link.replace("/>", " extra=\"value\"/>")), "_rels/.rels")
        rejects(parts().also { it["_rels/.rels"] = xml("<Wrong>$link</Wrong>") }, "_rels/.rels")
    }

    @Test fun `custom property structure and scalar values reject active nested or malformed content`() {
        for (content in listOf(property() + property(), property(pid = "1"), property(pid = "invalid"), property(name = ""),
            property(extra = "linkTarget=\"https://invalid.example/\""), property().replace("D5CDD505", "00000000"),
            property("<vt:blob>payload</vt:blob>"), property("<vt:vector/>"), property("<vt:lpwstr extra=\"1\">x</vt:lpwstr>"),
            property("<vt:lpwstr><vt:lpwstr>x</vt:lpwstr></vt:lpwstr>"), property("<vt:bool>yes</vt:bool>"),
            property("<vt:i4>abc</vt:i4>"), property("<vt:ui8>-1</vt:ui8>"), property("<vt:r8>NaN</vt:r8>"),
            property("<vt:filetime>invalid</vt:filetime>"), property(""), property().replace("<property ", "<foreign ").replace("</property>", "</foreign>"),
            "unexpected" + property())) rejects(parts(content), properties)
        rejects(parts().also { it[properties] = xml("<Properties/>") }, properties)
        rejects(parts().also { it[properties] = xml("""<Properties xmlns="$custom" extra="1"/>""") }, properties)
    }

    @Test fun `content type must identify exactly one custom properties override`() {
        for (type in listOf("<Types/>", """<Types><Override PartName="/$properties" ContentType="application/vnd.openxmlformats-officedocument.custom-properties+xml"/></Types>""",
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/$properties" ContentType="invalid"/></Types>""")) {
            rejects(parts().also { it["[Content_Types].xml"] = xml(type) }, "[Content_Types].xml")
        }
        rejects(parts().also { it.remove("[Content_Types].xml") }, "[Content_Types].xml")
    }
}

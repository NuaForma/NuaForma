package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordCustomXmlMetadataTest {

    private fun parts() = requireNotNull(javaClass.getResourceAsStream("/passive-office-package/a4/source.docx"))
        .use { PdfFixture.unzip(it.readBytes()).toMutableMap() }

    private fun normalize(input: Map<String, ByteArray>, context: GenerationContext = GenerationContext()): MutableMap<String, XmlElement> {
        val xml = ConversionXml(GenerationContext())
        val parsed = input.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.mapValues { (name, bytes) -> xml.readPart(bytes, name) }.toMutableMap()
        WordCustomXmlMetadata(context).removeFromPrintModel(parsed) { part, _, reason -> NativeDocxReadException(reason, part) }
        return parsed
    }

    @Test
    fun `recognized Office graphs keep source bytes and cannot become template instructions`() {
        val input = parts()
        val before = input.mapValues { it.value.copyOf() }
        val result = normalize(input)
        assertTrue(result.keys.none { it.startsWith("customXml/") })
        assertTrue(result.getValue("word/_rels/document.xml.rels").elements().none { it.attribute("", "Target")?.startsWith("../customXml/") == true })
        assertTrue(result.containsKey("word/styles.xml"))
        for ((name, bytes) in before) assertArrayEquals(bytes, input.getValue(name), name)
    }

    @Test
    fun `incomplete foreign ambiguous and active custom metadata remain rejected`() {
        for ((name, transform) in listOf<Pair<String, (String) -> String>>(
            "word/_rels/document.xml.rels" to { it.replace("Id=\"custom1\"", "Id=\"custom1\" TargetMode=\"External\"") },
            "word/_rels/document.xml.rels" to { it.replace("Target=\"../customXml/item1.xml\"", "Target=\"../customXml/missing.xml\"") },
            "customXml/_rels/item1.xml.rels" to { it.replace("itemProps1.xml", "../itemProps1.xml") },
            "customXml/itemProps1.xml" to { it.replace("00000000-0000-0000-0000-000000000001", "invalid") },
            "customXml/itemProps1.xml" to { it.replace("ds:schemaRefs", "ds:Unknown") },
            "customXml/item1.xml" to { it.replace("</m:contentTypeSchema>", "<w:p xmlns:w=\"${PdfFixture.W}\"/></m:contentTypeSchema>") },
            "word/document.xml" to { it.replace("<w:body>", "<w:body><w:dataBinding/>") })) {
            val input = parts()
            val before = input.getValue(name).decodeToString()
            val after = transform(before)
            assertNotEquals(before, after)
            input[name] = after.toByteArray()
            assertThrows(NativeDocxReadException::class.java) { normalize(input) }
        }
        val missing = parts().apply { remove("customXml/itemProps1.xml") }
        assertThrows(NativeDocxReadException::class.java) { normalize(missing) }
    }

    @Test
    fun `unbound arbitrary data is private while bibliography keeps its strict admission`() {
        val input = parts()
        input["customXml/item1.xml"] = "<Unknown>{NOT_A_TEMPLATE</Unknown>".toByteArray()
        assertTrue(normalize(input).keys.none { it.startsWith("customXml/") })
        input["customXml/item1.xml"] = ("<b:Unknown xmlns:b=\"http://schemas.openxmlformats.org/officeDocument/2006/bibliography\"/>").toByteArray()
        val result = normalize(input)
        assertTrue(result.containsKey("customXml/item1.xml"))
        assertThrows(NativeDocxReadException::class.java) { WordBibliographyMetadata(GenerationContext()).removeFromPrintModel(result) }
    }

    @Test
    fun `a declared bibliography schema keeps foreign roots in strict bibliography admission`() {
        val input = parts()
        input["customXml/item1.xml"] = "<Unknown>{NOT_A_TEMPLATE</Unknown>".toByteArray()
        val name = "customXml/itemProps1.xml"
        val before = input.getValue(name).decodeToString()
        val after = before.replace("http://schemas.microsoft.com/office/2006/metadata/contentType", "http://schemas.openxmlformats.org/officeDocument/2006/bibliography")
        assertNotEquals(before, after)
        input[name] = after.toByteArray()
        val result = normalize(input)
        assertTrue(result.containsKey("customXml/item1.xml"))
        assertThrows(NativeDocxReadException::class.java) { WordBibliographyMetadata(GenerationContext()).removeFromPrintModel(result) }
    }

    @Test
    fun `unbound data rejects executable layout namespaces and bounded graph violations`() {
        for (namespace in listOf(PdfFixture.W, "http://schemas.openxmlformats.org/drawingml/2006/main", "http://www.w3.org/1999/XSL/Transform")) {
            val input = parts()
            input["customXml/item1.xml"] = "<m:data xmlns:m=\"urn:nuaforma:test\"><x:child xmlns:x=\"$namespace\"/></m:data>".toByteArray()
            assertThrows(NativeDocxReadException::class.java) { normalize(input) }
        }
        val oversized = parts().apply { this["customXml/item1.xml"] = ("<data>" + "<n/>".repeat(16_384) + "</data>").toByteArray() }
        assertThrows(NativeDocxReadException::class.java) { normalize(oversized) }
        val shared = parts().apply {
            val name = "customXml/_rels/item2.xml.rels"
            this[name] = getValue(name).decodeToString().replace("itemProps2.xml", "itemProps1.xml").toByteArray()
        }
        assertThrows(NativeDocxReadException::class.java) { normalize(shared) }
    }

    @Test
    fun `unbound metadata type checks fail before private graph mutation`() {
        val input = parts()
        val name = "[Content_Types].xml"
        val original = input.getValue(name).decodeToString()
        val changed = original.replace("application/vnd.openxmlformats-officedocument.customXmlProperties+xml", "application/xml")
        assertNotEquals(original, changed)
        input[name] = changed.toByteArray()
        val xml = ConversionXml(GenerationContext())
        val parsed = input.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.mapValues { (part, bytes) -> xml.readPart(bytes, part) }.toMutableMap()
        val keys = parsed.keys.toSet()
        val main = parsed.getValue("word/_rels/document.xml.rels")
        val links = main.elements().toList()
        assertThrows(NativeDocxReadException::class.java) {
            WordCustomXmlMetadata(GenerationContext()).removeFromPrintModel(parsed) { part, _, reason -> NativeDocxReadException(reason, part) }
        }
        assertEquals(keys, parsed.keys)
        assertEquals(links, main.elements())
    }

    @Test
    fun `case-variant names require owned exact matching relationships`() {
        val input = parts()
        input["customXML/itemProps99.xml"] = input.getValue("customXml/itemProps1.xml")
        assertThrows(NativeDocxReadException::class.java) { normalize(input) }
        val mismatch = parts()
        val name = "word/_rels/document.xml.rels"
        mismatch[name] = mismatch.getValue(name).decodeToString().replace("../customXml/item1.xml", "../customXML/item1.xml").toByteArray()
        assertThrows(NativeDocxReadException::class.java) { normalize(mismatch) }
    }

    @Test
    fun `stored data still rejects malformed XML entities and processing instructions`() {
        for (content in listOf("<!DOCTYPE data [<!ENTITY value SYSTEM 'file:///not-read'>]><data>&value;</data>",
            "<?xml-stylesheet href='https://127.0.0.1:1/not-read'?><data/>", "<data><broken></data>")) {
            val input = parts().apply { this["customXml/item1.xml"] = content.toByteArray() }
            assertThrows(GenerationException::class.java) { normalize(input) }
        }
    }

    @Test
    fun `metadata graph scanning observes cancellation`() {
        assertThrows(GenerationException::class.java) { normalize(parts(), GenerationContext(cancellation = CancellationToken { true })) }
    }

}

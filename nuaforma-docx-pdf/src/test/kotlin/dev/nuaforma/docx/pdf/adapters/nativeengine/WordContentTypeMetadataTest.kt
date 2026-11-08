package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordContentTypeMetadataTest {

    private fun parts() = requireNotNull(javaClass.getResourceAsStream("/passive-office-package/a4/source.docx"))
        .use { PdfFixture.unzip(it.readBytes()).toMutableMap() }

    private fun normalize(input: Map<String, ByteArray>, context: GenerationContext = GenerationContext()): MutableMap<String, XmlElement> {
        val xml = ConversionXml(GenerationContext())
        val parsed = input.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.mapValues { (name, bytes) -> xml.readPart(bytes, name) }.toMutableMap()
        WordContentTypeMetadata(context).removeFromPrintModel(parsed) { part, _, reason -> NativeDocxReadException(reason, part) }
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
    fun `unrecognized roots stay available for the ordinary strict custom XML admission`() {
        val input = parts()
        input["customXml/item1.xml"] = "<Unknown/>".toByteArray()
        val result = normalize(input)
        assertTrue(result.containsKey("customXml/item1.xml"))
        assertThrows(NativeDocxReadException::class.java) { WordBibliographyMetadata(GenerationContext()).removeFromPrintModel(result) }
    }

    @Test
    fun `metadata graph scanning observes cancellation`() {
        assertThrows(GenerationException::class.java) { normalize(parts(), GenerationContext(cancellation = CancellationToken { true })) }
    }

}

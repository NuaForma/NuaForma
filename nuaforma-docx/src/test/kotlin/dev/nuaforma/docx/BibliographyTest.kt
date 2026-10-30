package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream

class BibliographyTest {
    private val bibliography = "http://schemas.openxmlformats.org/officeDocument/2006/bibliography"
    private fun parts(content: String): MutableMap<String, ByteArray> = SyntheticDocx.parts().apply {
        val source = "<b:Sources xmlns:b=\"$bibliography\" SelectedStyle=\"/APA.XSL\" StyleName=\"APA\" Version=\"6\">$content</b:Sources>"
        put("customXml/item1.xml", source.toByteArray())
        put("customXml/itemProps1.xml", "<ds:datastoreItem xmlns:ds=\"http://schemas.openxmlformats.org/officeDocument/2006/customXml\" ds:itemID=\"{A1E83116-3091-4B2E-94EA-CE8B1E3BF905}\"><ds:schemaRefs><ds:schemaRef ds:uri=\"$bibliography\"/></ds:schemaRefs></ds:datastoreItem>".toByteArray())
        put("customXml/_rels/item1.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"properties\" Type=\"${SyntheticDocx.R}/customXmlProps\" Target=\"itemProps1.xml\"/></Relationships>".toByteArray())
        put("word/_rels/document.xml.rels", getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>", "<Relationship Id=\"bibliography\" Type=\"${SyntheticDocx.R}/customXml\" Target=\"../customXml/item1.xml\"/></Relationships>").toByteArray())
        put("[Content_Types].xml", getValue("[Content_Types].xml").decodeToString().replace("</Types>", "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/customXml/itemProps1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.customXmlProperties+xml\"/></Types>").toByteArray())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `bibliography roots survive round trip and generation without evaluating metadata`(populated: Boolean) {
        val original = parts(if (populated) "<b:Source><b:Tag>REFERENCE-54</b:Tag><b:SourceType>Book</b:SourceType><b:Title>{NOT_A_TEMPLATE</b:Title></b:Source>" else "")
        val source = SyntheticDocx.zip(original)
        val roundTrip = ByteArrayOutputStream().also { output -> DocxDocuments.open(source.inputStream()).use { it.writeTo(output) } }.toByteArray()
        val generated = ByteArrayOutputStream().also { output ->
            DocxProvider().generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX)),
                mapOf("customer" to mapOf("name" to "Bibliography tenant"), "reference" to "BIB54")), output, GenerationContext())
        }.toByteArray()
        for ((bytes, generatedDocument) in listOf(roundTrip to false, generated to true)) {
            val restored = SyntheticDocx.unzip(bytes)
            assertEquals(original.keys, restored.keys)
            original.forEach { (name, value) ->
                if (!generatedDocument || name !in setOf("word/document.xml", "word/header1.xml", "word/footer1.xml")) {
                    if (name.endsWith(".xml") || name.endsWith(".rels")) assertEquals(SyntheticDocx.semantic(value), SyntheticDocx.semantic(restored.getValue(name)), name)
                    else assertArrayEquals(value, restored.getValue(name), name)
                }
            }
            if (generatedDocument) assertTrue(restored.getValue("word/document.xml").decodeToString().contains("Bibliography tenant"))
        }
    }

    @Test
    fun `unreachable bibliography ZIP items are omitted by owning package serialization`() {
        val original = parts("").apply { put("customXml/item2.xml", getValue("customXml/item1.xml")) }
        val output = ByteArrayOutputStream()
        DocxDocuments.open(SyntheticDocx.zip(original).inputStream()).use { it.writeTo(output) }
        val restored = SyntheticDocx.unzip(output.toByteArray())
        assertFalse(restored.containsKey("customXml/item2.xml"))
        assertEquals(SyntheticDocx.semantic(original.getValue("customXml/item1.xml")), SyntheticDocx.semantic(restored.getValue("customXml/item1.xml")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["<b:Unknown>PRIVATE-METADATA</b:Unknown>", "<b:Source>PRIVATE-METADATA"])
    fun `invalid structured bibliography still fails without partial publication`(invalid: String) {
        val output = ByteArrayOutputStream()
        val fault = assertThrows(GenerationException::class.java) {
            DocxProvider().generate(GenerationRequest(SyntheticDocx.zip(parts(invalid)).inputStream(),
                GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX))), output, GenerationContext())
        }
        assertEquals(DiagnosticCodes.INVALID_TEMPLATE, fault.diagnostics.first().code)
        assertFalse(fault.diagnostics.toString().contains("PRIVATE-METADATA"))
        assertEquals(0, output.size())
    }
}

package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordGridHistoryMetadataTest {
    private val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
    private val xml = ConversionXml(context)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun history(widths: String = "<w:gridCol w:w=\"819.2\"/><w:gridCol w:w=\"409.6\"/>", attributes: String = "w:id=\"7\"") =
        "<w:tblGridChange $attributes><w:tblGrid>$widths</w:tblGrid></w:tblGridChange>"

    private fun root(record: String) = xml.read("<w:document xmlns:w=\"$word\"><w:body><w:tbl><w:tblGrid><w:gridCol w:w=\"1000.8\"/>$record</w:tblGrid></w:tbl></w:body></w:document>".toByteArray())

    private fun normalize(root: XmlElement, source: NativeDocxSource = NativeDocxSource(context)): Boolean {
        source.index(root, "word/document.xml")
        return WordGridHistoryMetadata.normalizeSource(root, context) { node, message ->
            source.current = node
            throw source.failure(message, "word/document.xml")
        }
    }

    @Test
    fun `history normalization retains the current grid and captures canonical old values at original routes`() {
        val root = root(history(attributes = "w:id=\"0\" w:author=\"Synthetic {literal\" w:date=\"2025-01-01T00:00:00Z\""))
        val source = NativeDocxSource(context)
        source.index(root, "word/document.xml")
        val observe = WordSourceNormalization.observe(root, "word/document.xml", source)
        assertTrue(normalize(root, source))
        val columns = root.descendants().filter { it.name.localPart == "gridCol" }.toList()
        assertEquals(listOf("1000.8"), columns.map { it.attribute(word, "w") })
        val changes = observe()
        assertEquals(listOf("819", "409"), changes.map { it.attributes.values.single() })
        assertTrue(changes.all { it.route.any { (name, _) -> name.localPart == "tblGridChange" } })
        assertFalse(normalize(root))
    }

    @Test
    fun `invalid identifiers editing metadata structures and old widths retain original diagnostics`() {
        val records = mutableListOf(
            history(attributes = ""), history(attributes = "w:id=\"-1\""), history(attributes = "w:id=\"2147483648\""),
            history(attributes = "w:id=\"7\" unexpected=\"1\""), history(attributes = "w:id=\"7\" w:author=\"${"A".repeat(256)}\""),
            history(attributes = "w:id=\"7\" w:date=\"2025-02-30T00:00:00Z\""), history(widths = ""),
            history(widths = "<w:gridCol w:w=\"1\"/>".repeat(17)),
            history(widths = "<w:gridCol w:w=\"10\"/><w:ins/>"),
            history(widths = "<w:gridCol w:w=\"10\"><w:t>{hidden</w:t></w:gridCol>"),
        )
        for (value in listOf("-1", "0", "288000.1", "NaN", "1e3", "1.0000000000000000001"))
            records += history(widths = "<w:gridCol w:w=\"$value\"/>")
        for (record in records) {
            val failure = assertThrows(NativeDocxReadException::class.java) { normalize(root(record)) }
            assertEquals("word/document.xml", failure.part)
            assertTrue(failure.sourceContext.path.orEmpty().contains("tblGridChange"), record)
        }
    }

    @Test
    fun `duplicate records identify the second history and foreign grid namespaces reject`() {
        val duplicate = assertThrows(NativeDocxReadException::class.java) { normalize(root(history() + history())) }
        assertTrue(duplicate.sourceContext.path.orEmpty().endsWith("tblGridChange[2]"))
        for (record in listOf(history().replace("w:tblGridChange", "f:tblGridChange").replace("w:id=", "xmlns:f=\"urn:foreign\" w:id="),
            history().replace("<w:tblGrid>", "<f:tblGrid xmlns:f=\"urn:foreign\">").replace("</w:tblGrid>", "</f:tblGrid>")))
            assertThrows(NativeDocxReadException::class.java) { normalize(root(record)) }
    }

    @Test
    fun `unrelated data namespace is untouched`() {
        val root = xml.read("<data xmlns=\"urn:unrelated\"><tblGrid><tblGridChange id=\"bad\"/></tblGrid></data>".toByteArray())
        val before = xml.write(root)
        assertFalse(normalize(root))
        assertArrayEquals(before, xml.write(root))
    }
}

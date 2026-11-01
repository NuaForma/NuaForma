package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeDocxSourceTest {
    @Test fun `nested table row cell and header coordinates identify actual nodes`() {
        for (part in listOf("word/document.xml", "word/header1.xml", "word/footer1.xml")) {
            val context = GenerationContext(dev.nuaforma.contracts.GenerationLogging(dev.nuaforma.contracts.LoggingMode.DEVELOPMENT))
            val xml = ConversionXml(context).read(("<w:root xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                "<w:p><w:r><w:t>First</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:pPr><w:pBdr/></w:pPr><w:r><w:t>Target</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:root>").toByteArray())
            val source = NativeDocxSource(context)
            source.index(xml, part)
            source.current = xml.descendants().single { it.name.localPart == "pBdr" }
            val error = source.failure("Unsupported document feature", "package")
            assertEquals(part, error.part)
            assertEquals("paragraph:2", error.documentLocation)
            assertEquals("/w:root[1]/w:tbl[1]/w:tr[1]/w:tc[1]/w:p[1]/w:pPr[1]/w:pBdr[1]", error.sourceContext!!.path)
            assertEquals("Target", error.sourceContext!!.excerpt)
            assertTrue(error.location!!.startsWith("xml:1:"))
        }
    }

    @Test fun `style errors have no invented paragraph and secret excerpts are redacted`() {
        val context = GenerationContext(dev.nuaforma.contracts.GenerationLogging(dev.nuaforma.contracts.LoggingMode.DEVELOPMENT))
        val xml = ConversionXml(context).read("""<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style><w:pPr><w:pBdr/></w:pPr></w:style></w:styles>""".toByteArray())
        val source = NativeDocxSource(context)
        source.index(xml, "word/styles.xml")
        source.current = xml.descendants().single { it.name.localPart == "pBdr" }
        val error = source.failure("Unsupported document feature", "package")
        assertNull(error.documentLocation)
        assertEquals("word/styles.xml", error.part)
        assertTrue(error.sourceContext!!.path.contains("/w:style[1]/"))
        val sensitive = dev.nuaforma.contracts.DocumentSourceContext("Unsupported formatting", "/w:p[1]", "w:p", "password PRIVATE_VALUE")
        assertEquals("[redacted expression]", sensitive.excerpt)
    }
}

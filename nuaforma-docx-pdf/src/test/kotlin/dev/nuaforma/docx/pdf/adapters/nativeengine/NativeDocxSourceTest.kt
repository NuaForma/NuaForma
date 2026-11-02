package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeDocxSourceTest {

    @Test
    fun `unsupported ZIP entry identifies the actual package part without inventing a paragraph`() {
        val bytes = java.io.ByteArrayOutputStream().also { output ->
            java.util.zip.ZipOutputStream(output).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("word/media/image1.jpeg"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }.toByteArray()
        val context = GenerationContext(dev.nuaforma.contracts.GenerationLogging(dev.nuaforma.contracts.LoggingMode.DEVELOPMENT))
        val error = assertThrows(NativeDocxReadException::class.java) {
            NativeDocxReader(context) { _, _, _ -> fail("Package rejection must precede font resolution") }.read(bytes)
        }
        assertEquals("word/media/image1.jpeg", error.part)
        assertEquals("Unsupported package part", error.sourceContext.explanation)
        assertNull(error.documentLocation)
        assertNull(error.location)
        assertNull(error.sourceContext.path)
    }

    @Test fun `block errors excerpt their own content rather than an unrelated first paragraph`() {
        val context = GenerationContext(dev.nuaforma.contracts.GenerationLogging(dev.nuaforma.contracts.LoggingMode.DEVELOPMENT))
        val xml = ConversionXml(context).read("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Unrelated first paragraph</w:t></w:r></w:p><w:sdt><w:sdtContent><w:p><w:r><w:t>Actual control content</w:t></w:r></w:p></w:sdtContent></w:sdt></w:body></w:document>""".toByteArray())
        val source = NativeDocxSource(context)
        source.index(xml, "word/document.xml")
        source.current = xml.descendants().single { it.name.localPart == "sdt" }
        val error = source.failure("Unsupported document feature", "package")
        assertEquals("Actual control content", error.sourceContext.excerpt)
        assertTrue(error.sourceContext.path?.endsWith("/w:sdt[1]") == true)
        assertNull(error.documentLocation)
    }

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
            assertEquals("/w:root[1]/w:tbl[1]/w:tr[1]/w:tc[1]/w:p[1]/w:pPr[1]/w:pBdr[1]", error.sourceContext.path)
            assertEquals("Target", error.sourceContext.excerpt)
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
        assertTrue(error.sourceContext.path?.contains("/w:style[1]/") == true)
        val sensitive = dev.nuaforma.contracts.DocumentSourceContext("Unsupported formatting", "/w:p[1]", "w:p", "password PRIVATE_VALUE")
        assertEquals("[redacted expression]", sensitive.excerpt)
    }
    @Test fun `unmapped native failures retain the concrete reason without fabricated coordinates`() {
        val error = NativeDocxReadException("Custom footnote marks are unsupported", "word/footnotes.xml")
        assertEquals("Custom footnote marks are unsupported", error.sourceContext.explanation)
        assertNull(error.sourceContext.path)
        assertNull(error.sourceContext.construct)
        assertNull(error.documentLocation)
        assertNull(error.location)
        assertEquals("", error.sourceContext.excerpt)
    }

}

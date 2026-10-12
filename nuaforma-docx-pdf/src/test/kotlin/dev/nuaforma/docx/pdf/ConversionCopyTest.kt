package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.*
import dev.nuaforma.docx.pdf.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ConversionCopyTest {
    private fun parts(block: String): MutableMap<String, ByteArray> = PdfFixture.parts().apply {
        this["word/document.xml"] = getValue("word/document.xml").decodeToString().replace("<w:body>", "<w:body>$block").toByteArray()
        this["opaque.bin"] = byteArrayOf(1, 2, 3, 4)
    }

    @Test
    fun `geometry is native namespace aware and confined to the private copy`() {
        val source = parts(PdfFixture.inline() + PdfFixture.margin())
        val bytes = PdfFixture.zip(source)
        ConversionFiles.create(GenerationContext()).use { files ->
            Files.write(files.docx, bytes)
            assertEquals(2, ConversionCopy.prepare(files.docx, files.conversionCopy, PdfOptions.read(emptyMap()), GenerationContext()).normalizedShapes)
            assertArrayEquals(bytes, Files.readAllBytes(files.docx))
            val result = PdfFixture.unzip(Files.readAllBytes(files.conversionCopy))
            (source.keys - "word/document.xml").forEach { assertArrayEquals(source.getValue(it), result.getValue(it), it) }
            val root = ConversionXml(GenerationContext()).read(result.getValue("word/document.xml"))
            val names = root.descendants().map { it.name.localPart }.toList()
            assertFalse("shape" in names); assertTrue("inline" in names); assertTrue("anchor" in names)
            val nativeText = root.descendants().single { it.children.any { child -> child is XmlText && child.text == "Native text" } }
            assertEquals("retained", nativeText.attribute("urn:synthetic-preserved", "attribute"))
            assertTrue(root.descendants().any { it.name.localPart == "bodyPr" && it.attribute("", "vert") == "vert270" })
            val extents = root.descendants().filter { it.name.localPart == "extent" }.map { it.attribute("", "cx") to it.attribute("", "cy") }.toSet()
            assertTrue("533400" to "2286000" in extents)
            val ids = root.descendants().filter { it.name.localPart == "docPr" }.map { it.attribute("", "id") }.toList()
            assertEquals(ids.distinct(), ids)
        }
    }

    @Test
    fun `unsupported geometry fails instead of replacing text by an image or shifting constants`() {
        for (box in listOf(PdfFixture.margin().replace("rotation:90", "rotation:45"),
            PdfFixture.margin().replace("height:42pt", "height:100pt"), PdfFixture.inline().replace("height:35pt", "height:auto"),
            """<v:shapetype id="_x0000_t202" filled="f"/>""" + PdfFixture.inline())) {
            ConversionFiles.create(GenerationContext()).use { files ->
                Files.write(files.docx, PdfFixture.zip(parts(box)))
                assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, assertThrows(GenerationException::class.java) {
                    ConversionCopy.prepare(files.docx, files.conversionCopy, PdfOptions.read(emptyMap()), GenerationContext())
                }.diagnostics.single().code)
            }
        }
    }

    @Test
    fun `paragraph defaults fill missing fields and never override alternative modes`() {
        val xml = ConversionXml(GenerationContext())
        val defaults = PdfOptions.ParagraphDefaults(160, 278)
        val root = xml.read(PdfFixture.parts().getValue("word/styles.xml"))
        ParagraphFallbacks.apply(root, defaults)
        val spacing = root.descendants().single { it.name.localPart == "spacing" }
        assertEquals("160", spacing.attribute(PdfFixture.W, "after")); assertEquals("278", spacing.attribute(PdfFixture.W, "line"))
        val first = xml.write(root)
        ParagraphFallbacks.apply(root, defaults)
        assertArrayEquals(first, xml.write(root))
        val alternatives = xml.read("""<w:styles xmlns:w="${PdfFixture.W}"><w:docDefaults><w:pPrDefault><w:pPr><w:spacing w:afterAutospacing="0" w:lineRule="exact"/></w:pPr></w:pPrDefault></w:docDefaults></w:styles>""".toByteArray())
        val unchanged = xml.write(alternatives)
        ParagraphFallbacks.apply(alternatives, defaults)
        assertArrayEquals(unchanged, xml.write(alternatives))
    }

    @Test
    fun `XML entity instructions and retrieval fields are rejected without consuming host files`() {
        val xml = ConversionXml(GenerationContext())
        for (unsafe in listOf("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><a>&x;</a>", "<?xml-stylesheet href='file:///etc/passwd'?><a/>")) {
            assertEquals(DiagnosticCodes.INVALID_TEMPLATE, assertThrows(GenerationException::class.java) { xml.read(unsafe.toByteArray()) }.diagnostics.single().code)
        }
        val fields = xml.read("""<w:document xmlns:w="${PdfFixture.W}"><w:body><w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText>INCLUDE</w:instrText></w:r><w:r><w:instrText>TEXT "file:///etc/passwd"</w:instrText></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p></w:body></w:document>""".toByteArray())
        assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, assertThrows(GenerationException::class.java) { ConversionSafety.validate(fields) }.diagnostics.single().code)
        val carriageReturn = xml.read("""<w:document xmlns:w="${PdfFixture.W}"><w:body><w:fldSimple w:instr="INCLUDETEXT&#13;file:///etc/passwd"/></w:body></w:document>""".toByteArray())
        assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, assertThrows(GenerationException::class.java) { ConversionSafety.validate(carriageReturn) }.diagnostics.single().code)
    }
}

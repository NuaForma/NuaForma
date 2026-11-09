package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeParagraphNumberingSourceTest {
    @TempDir lateinit var temporary: Path
    private val sourcePath = "/word-numbering-style/inherited-without-effects/a4/source.docx"

    @Test
    fun `invalid original numbering cannot disappear through binding when no effects cache exists`() {
        val support = NativeWordFeatureSupport(temporary)
        for ((before, after, element) in listOf(
            Triple("<w:numPr><w:ilvl w:val=\"1\"/>", "<w:numPr unexpected=\"1\"><w:ilvl w:val=\"1\"/>", "numPr"),
            Triple("<w:ilvl w:val=\"1\"/>", "<w:ilvl w:val=\"1\" unexpected=\"1\"/>", "ilvl"),
            Triple("<w:ilvl w:val=\"1\"/>", "<w:ilvl w:val=\"1\"/><w:ilvl w:val=\"2\"/>", "ilvl"),
            Triple("<w:ilvl w:val=\"1\"/>", "<w:ilvl w:val=\"1\">{hidden</w:ilvl>", "ilvl"),
            Triple("<w:numId w:val=\"1\"/>", "<w:numId w:val=\"1e3\"/>", "numId"))) {
            support.rejectChanged(sourcePath, "word/styles.xml", element) { it.replaceFirst(before, after) }
        }
    }

    @Test
    fun `cleanup removes an invalid property attribute while retaining valid inherited numbering`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes(sourcePath)).toMutableMap()
        val original = parts.getValue("word/styles.xml").decodeToString()
        val changed = original.replaceFirst("<w:numPr><w:ilvl w:val=\"1\"/>", "<w:numPr unexpected=\"1\"><w:ilvl w:val=\"1\"/>")
        assertNotEquals(original, changed)
        parts["word/styles.xml"] = changed.toByteArray()
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        val output = ByteArrayOutputStream()
        val report = support.provider.cleanTemplate(PdfFixture.zip(parts).inputStream(), output, context)
        assertEquals(1, report.changes.size)
        assertEquals(TemplateCleanupAction.REMOVE_ATTRIBUTE, report.changes.single().action)
        assertEquals("word/styles.xml", report.changes.single().part)
        val cleaned = PdfFixture.unzip(output.toByteArray())
        val root = ConversionXml(context).readPart(cleaned.getValue("word/styles.xml"), "word/styles.xml")
        val properties = root.descendants().single { it.name.localPart == "numPr" && it.elements().any { child -> child.name.localPart == "ilvl" } }
        assertTrue(properties.attributes.isEmpty())
        val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        assertEquals("1", properties.elements().single { it.name.localPart == "ilvl" }.attribute(word, "val"))
        assertEquals("1", properties.elements().single { it.name.localPart == "numId" }.attribute(word, "val"))
        for ((part, bytes) in parts) if (part != "word/styles.xml") assertArrayEquals(bytes, cleaned[part], part)
        assertTrue(support.generate(output.toByteArray(), "standard").isNotEmpty())
    }

    @Test
    fun `nested marker text in a formatting property is a source error rather than an expression`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes(sourcePath)).toMutableMap()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString()
            .replaceFirst("<w:ilvl w:val=\"1\"/>", "<w:ilvl w:val=\"1\"><w:t>{hidden</w:t></w:ilvl>").toByteArray()
        val output = ByteArrayOutputStream()
        val failure = assertThrows(GenerationException::class.java) { support.generate(PdfFixture.zip(parts), "standard", output) }
        assertEquals(0, output.size())
        assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, failure.diagnostics.single().code)
        assertEquals("word/styles.xml", failure.diagnostics.single().part)
        assertTrue(failure.diagnostics.single().sourceContext?.path.orEmpty().endsWith("ilvl[1]"))
    }
}

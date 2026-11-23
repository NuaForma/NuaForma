package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeIntelligenceMetadataTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun emptyOfficeEditorContainersPreservePrintedTextAndCleanupBytes() {
        val support = NativeWordFeatureSupport(temporary)
        val control = source(null)
        val withMetadata = source(empty)
        for (profile in listOf("standard", "pdfa-3b")) {
            fun content(bytes: ByteArray) = Loader.loadPDF(support.generate(bytes, profile)).use { pdf ->
                pdf.numberOfPages to PDFTextStripper().getText(pdf)
            }
            val expected = content(control)
            assertTrue(expected.second.contains("Passive package metadata"))
            assertEquals(expected, content(withMetadata))
        }
        val output = ByteArrayOutputStream()
        val report = support.provider.cleanTemplate(withMetadata.inputStream(), output,
            GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        assertTrue(report.changes.isEmpty())
        val contents = PdfFixture.unzip(withMetadata)
        val cleaned = PdfFixture.unzip(output.toByteArray())
        assertEquals(contents.keys, cleaned.keys)
        contents.forEach { (name, bytes) -> assertArrayEquals(bytes, cleaned[name], name) }
    }

    @Test
    fun nestedAndForeignEditorContentRejectsAndCanBeCleaned() {
        val support = NativeWordFeatureSupport(temporary)
        val nested = empty.replace("<ai:onDemandWorkflows/>", "<ai:onDemandWorkflows><ai:onDemandWorkflow/></ai:onDemandWorkflows>")
        val foreign = empty.replace("<ai:observations/>", "<observations/>")
        for (xml in listOf(nested, foreign)) {
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) { support.generate(source(xml), "standard", output) }
            assertEquals(0, output.size())
            val diagnostic = error.diagnostics.single()
            assertEquals("word/intelligence2.xml", diagnostic.part)
            assertTrue(diagnostic.sourceContext?.explanation.orEmpty().contains("intelligence metadata"))
        }
        val cleaned = ByteArrayOutputStream()
        val report = support.provider.cleanTemplate(source(nested).inputStream(), cleaned,
            GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        assertTrue(report.changes.any { it.part == "word/intelligence2.xml" && it.action == TemplateCleanupAction.REMOVE_PART })
        assertFalse(PdfFixture.unzip(cleaned.toByteArray()).containsKey("word/intelligence2.xml"))
        assertTrue(support.generate(cleaned.toByteArray(), "standard").isNotEmpty())
    }

    private fun source(metadata: String?): ByteArray {
        val parts = PdfFixture.parts()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
            .replace("Hello {name}", "Passive package metadata").toByteArray()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString()
            .replace("DejaVu Sans", "Liberation Sans").toByteArray()
        if (metadata != null) {
            val entry = """<Override PartName="/word/intelligence2.xml" ContentType="application/vnd.ms-office.intelligence2+xml"/>"""
            val relationship = """<Relationship Id="intelligenceFixture" Type="http://schemas.microsoft.com/office/2020/10/relationships/intelligence" Target="intelligence2.xml"/>"""
            val types = "[Content_Types].xml"
            parts[types] = parts.getValue(types).decodeToString().replace("</Types>", entry + "</Types>").toByteArray()
            val rels = "word/_rels/document.xml.rels"
            parts[rels] = parts.getValue(rels).decodeToString().replace("</Relationships>", relationship + "</Relationships>").toByteArray()
            parts["word/intelligence2.xml"] = metadata.toByteArray()
        }
        return PdfFixture.zip(parts)
    }

    private val empty = """<ai:intelligence xmlns:ai="http://schemas.microsoft.com/office/intelligence/2020/intelligence">""" +
        "<ai:observations/><ai:intelligenceSettings/><ai:onDemandWorkflows/></ai:intelligence>"
}

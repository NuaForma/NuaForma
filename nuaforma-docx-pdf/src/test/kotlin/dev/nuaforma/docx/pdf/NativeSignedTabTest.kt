package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeSignedTabTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `Word skips dormant negative custom stops and uses the signed automatic grid`() {
        val negative = """<w:tab w:val="left" w:pos="-900"/>"""
        val positive = """<w:tab w:val="left" w:pos="2400"/>"""
        val expectedX = 56.7f
        assertEquals(expectedX - 36f, markerX(negative), .05f)
        assertEquals(markerX(""), markerX(negative), .01f)
        assertEquals(expectedX + 120f, markerX(negative + positive), .05f)
        assertEquals(markerX(positive), markerX(negative + positive), .01f)
        assertEquals(expectedX + 36f, markerX(negative, "WWWWWWWW"), .05f)
    }

    @Test
    fun `out of range or unsupported signs report the source tab stop`() {
        val support = NativeWordFeatureSupport(temporary)
        for (invalid in listOf("""<w:tab w:val="left" w:pos="-288001"/>""",
            """<w:tab w:val="right" w:pos="-900"/>""", """<w:tab w:val="left" w:pos="-900" w:leader="dot"/>""")) {
            support.rejectChanged(source, part, "tab") { fixture(it, invalid).replace("{prefix}", "I") }
        }
    }

    private fun markerX(stops: String, value: String = "I"): Float {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes(source)).toMutableMap()
        parts[part] = fixture(parts.getValue(part).decodeToString(), stops).toByteArray()
        val output = ByteArrayOutputStream()
        support.provider.generate(GenerationRequest(PdfFixture.zip(parts).inputStream(), GenerationRequirements(
            FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF)), mapOf("prefix" to value)),
            output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        return Loader.loadPDF(output.toByteArray()).use { pdf ->
            support.positions(pdf, Regex("MARKER")).getValue("MARKER").x
        }
    }

    private fun fixture(xml: String, stops: String): String = xml.substringBefore("<w:body>") +
        """<w:body><w:p><w:pPr><w:ind w:left="-1060"/><w:spacing w:line="280" w:lineRule="exact"/>""" +
        "<w:tabs>$stops</w:tabs></w:pPr>" +
        "<w:r><w:t>{prefix}</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>MARKER</w:t></w:r></w:p>" +
        "<w:sectPr>" + xml.substringAfter("<w:sectPr>")

    private companion object {
        const val source = "/floating-drawings/control/a4/source.docx"
        const val part = "word/document.xml"
    }
}

package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeHorizontalSectionTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `explicit default horizontal section flow retains existing actual Word and native references`(paper: String) {
        NativeWordFeatureSupport(temporary).verify("/horizontal-section-flow/$paper/", if (paper == "a4")
            listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard"),
            Regex("(?!)"), 0, golden = "/preferred-table-widths/$paper/page-1.png")
    }

    @Test
    fun `vertical rotated unknown duplicate and malformed section directions retain original diagnostics`() {
        val support = NativeWordFeatureSupport(temporary)
        for (replacement in listOf("<w:textDirection w:val=\"tbRl\"/>", "<w:textDirection w:val=\"lrTbV\"/>",
            "<w:textDirection w:val=\"unknown\"/>", "<w:textDirection/>", "<w:textDirection val=\"lrTb\"/>",
            "<w:textDirection w:val=\"lrTb\"><w:extra/></w:textDirection>",
            "<w:textDirection w:val=\"lrTb\"/><w:textDirection w:val=\"lrTb\"/>")) {
            support.rejectChanged("/horizontal-section-flow/a4/source.docx", "word/document.xml", "textDirection") {
                it.replace("<w:textDirection w:val=\"lrTb\"/>", replacement)
            }
        }
    }

    @Test
    fun `cleanup and ordinary DOCX output preserve the declared default direction`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = support.bytes("/horizontal-section-flow/a4/source.docx")
        val cleaned = ByteArrayOutputStream()
        assertTrue(support.provider.cleanTemplate(source.inputStream(), cleaned, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
        assertArrayEquals(source, cleaned.toByteArray())
        val output = ByteArrayOutputStream()
        support.provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.DOCX))), output, GenerationContext())
        assertTrue(PdfFixture.unzip(output.toByteArray()).getValue("word/document.xml").decodeToString().contains("textDirection"))
    }

}

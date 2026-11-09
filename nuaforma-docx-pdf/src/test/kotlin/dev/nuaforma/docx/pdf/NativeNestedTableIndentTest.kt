package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeNestedTableIndentTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `nested negative zero and positive indentation retain actual Word placement`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (mode in listOf("12", "15")) for (kind in listOf("negative", "zero", "positive")) {
            val profiles = if (kind == "negative" && paper == "a4")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/nested-table-indent/$mode-$kind/$paper/", profiles, Regex("I[0-9]{2}[AB]"), 6,
                goldenDirectory = "/nested-table-indent/$mode-${if (kind == "negative") "zero" else kind}/$paper/")
        }
    }

    @Test
    fun `cleanup retains supported nested geometry and original template bytes`() {
        val support = NativeWordFeatureSupport(temporary)
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        for (mode in listOf("12", "15")) {
            val source = support.bytes("/nested-table-indent/$mode-negative/a4/source.docx")
            val output = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(source.inputStream(), output, context).changes.isEmpty())
            assertArrayEquals(source, output.toByteArray())
            val second = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(output.toByteArray().inputStream(), second, context).changes.isEmpty())
            assertArrayEquals(output.toByteArray(), second.toByteArray())
        }
    }

    @Test
    fun `nested tables cannot exceed the containing cell width`() {
        val support = NativeWordFeatureSupport(temporary)
        for (mode in listOf("12", "15")) {
            val parts = PdfFixture.unzip(support.bytes("/nested-table-indent/$mode-negative/a4/source.docx")).toMutableMap()
            val original = parts.getValue("word/document.xml").decodeToString()
            val changed = original.replace("w:w=\"-100\"", "w:w=\"4000\"")
            assertNotEquals(original, changed)
            parts["word/document.xml"] = changed.toByteArray()
            val output = ByteArrayOutputStream()
            val failure = assertThrows(GenerationException::class.java) { support.generate(PdfFixture.zip(parts), "standard", output) }
            assertEquals(0, output.size())
            assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, failure.diagnostics.single().code)
            assertEquals(RejectionReason.NATIVE_PDF_RENDER_REJECTED, failure.diagnostics.single().rejectionReason)
        }
    }
}

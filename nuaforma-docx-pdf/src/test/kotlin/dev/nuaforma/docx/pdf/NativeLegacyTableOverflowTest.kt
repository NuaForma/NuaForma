package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeLegacyTableOverflowTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `legacy signed percentage and automatic widths retain Word placement`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (kind in listOf("autofit-physical", "fit-contents", "percent-50", "percent-100")) for (sign in listOf("negative", "zero")) {
            val folder = "/legacy-table-overflow/12-$kind-$sign/$paper/"
            val golden = if (kind != "autofit-physical") folder + "page-1.png"
                else if (sign == "negative") "/signed-table-indent/12-truncate/$paper/page-1.png"
                else "/implicit-table-indent/12-explicit/$paper/page-1.png"
            val profiles = if (kind == "percent-100" && paper == "a4")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify(folder, profiles, Regex("I[0-9]{2}[AB]"), 6, golden = golden)
        }
    }

    @Test
    fun `cleanup retains supported percentage widths without changing template bytes`() {
        val support = NativeWordFeatureSupport(temporary)
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        for (sign in listOf("negative", "zero")) {
            val source = support.bytes("/legacy-table-overflow/12-percent-100-$sign/a4/source.docx")
            val output = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(source.inputStream(), output, context).changes.isEmpty())
            assertArrayEquals(source, output.toByteArray())
            val again = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(output.toByteArray().inputStream(), again, context).changes.isEmpty())
            assertArrayEquals(output.toByteArray(), again.toByteArray())
        }
    }

    @Test
    fun `source percentage preference cannot place the table beyond the physical page`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = support.bytes("/legacy-table-overflow/12-percent-100-zero/a4/source.docx")
        val parts = PdfFixture.unzip(source).toMutableMap()
        val original = parts.getValue("word/document.xml").decodeToString()
        val changed = original.replace(Regex("""(<w:pgMar\b[^>]*\bw:right=")[0-9]+""")) { "${it.groupValues[1]}0" }
        assertNotEquals(original, changed)
        parts["word/document.xml"] = changed.toByteArray()
        val output = ByteArrayOutputStream()
        val failure = assertThrows(GenerationException::class.java) { support.generate(PdfFixture.zip(parts), "standard", output) }
        assertEquals(0, output.size())
        assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, failure.diagnostics.single().code)
        assertEquals(RejectionReason.NATIVE_PDF_RENDER_REJECTED, failure.diagnostics.single().rejectionReason)
    }
}

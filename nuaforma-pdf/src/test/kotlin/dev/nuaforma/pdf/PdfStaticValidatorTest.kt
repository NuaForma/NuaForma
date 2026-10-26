package dev.nuaforma.pdf

import dev.nuaforma.pdf.adapters.pdfbox.PdfStaticValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

class PdfStaticValidatorTest {
    @TempDir lateinit var temporary: Path

    /** Independent PDF syntax also retains deliberately unreferenced objects. */
    private fun pdf(catalog: String = "", page: String = "", extra: List<String> = emptyList(),
                    pages: String = "/Kids [3 0 R] /Count 1", version: String = "1.7", box: String = "0 0 595 842"): ByteArray {
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R $catalog >>", "<< /Type /Pages $pages >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [$box] $page >>") + extra
        val output = ByteArrayOutputStream()
        fun write(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
        write("%PDF-$version\n")
        val offsets = objects.mapIndexed { index, value -> output.size().also { write("${index + 1} 0 obj\n$value\nendobj\n") } }
        val xref = output.size()
        write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(java.util.Locale.ROOT, it)) }
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return output.toByteArray()
    }

    private fun validate(bytes: ByteArray, decoded: Long = 67_108_864, control: PdfRenderControl = PdfRenderControl(), version: String = "1.7") {
        val file = Files.write(temporary.resolve("candidate.pdf"), bytes)
        PdfStaticValidator.validate(file, decoded, control, version)
    }

    private fun rejected(bytes: ByteArray, code: PdfFailureCode = PdfFailureCode.VALIDATION_FAILED) {
        assertEquals(code, assertThrows(PdfRenderException::class.java) { validate(bytes) }.code)
    }

    @Test fun `real rendered fonts images and valid page structures pass without changing bytes`() {
        val bytes = PdfEngine().render(FoundationLetter.document(), FoundationLetter.fonts())
        validate(bytes)
        assertArrayEquals(bytes, Files.readAllBytes(temporary.resolve("candidate.pdf")))
        validate(pdf())
        validate(pdf(version = "1.4"), version = "1.4")
        rejected("%PDF-1.7\nsynthetic\n%%EOF\n".toByteArray())
        rejected(pdf(version = "1.4"))
        rejected(pdf(pages = "/Kids [] /Count 0"))
        rejected(pdf().decodeToString().replace("/Root 1 0 R", "/Root 99 0 R").toByteArray())
    }

    @Test fun `actions forms attachments and external streams reject even in orphan objects`() {
        for (key in listOf("OpenAction", "AA", "JavaScript", "JS", "AcroForm", "XFA", "EmbeddedFiles", "EF", "AF", "FFilter", "FDecodeParms")) {
            rejected(pdf(extra = listOf("<< /$key << >> >>")))
        }
        for (subtype in listOf("Widget", "RichMedia", "Movie", "Sound", "Screen", "FileAttachment")) {
            rejected(pdf(extra = listOf("<< /Subtype /$subtype >>")))
        }
        rejected(pdf(extra = listOf("<< /Type /EmbeddedFile /Length 1 >>\nstream\nx\nendstream")))
        rejected(pdf(extra = listOf("<< /Length 1 /F (external) >>\nstream\nx\nendstream")))
        for (action in listOf("Launch", "GoToR", "JavaScript", "SubmitForm", "UnknownAction")) {
            rejected(pdf(extra = listOf("<< /Type /Action /S /$action >>")))
        }
        for (uri in listOf("file:///private", "ftp://example.invalid/file", "relative/path", "https://example.invalid/bad space")) {
            rejected(pdf(extra = listOf("<< /S /URI /URI ($uri) >>")))
        }
        rejected(pdf(extra = listOf("<< /Type /Action /S /URI >>")))
        for (uri in listOf("https://example.invalid", "http://example.invalid", "mailto:qa@example.invalid")) {
            validate(pdf(extra = listOf("<< /Type /Action /S /URI /URI ($uri) >>")))
        }
        validate(pdf(extra = listOf("<< /Type /Action /S /GoTo /D [3 0 R /Fit] >>")))
    }

    @Test fun `invalid font resources descendants and missing or ambiguous font files reject`() {
        val fonts = listOf("(not a dictionary)", "<< /Subtype /TrueType >>", "<< /Type /Font /Subtype /Type3 >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", "<< /Type /Font /Subtype /Type0 >>",
            "<< /Type /Font /Subtype /Type0 /DescendantFonts [] >>", "<< /Type /Font /Subtype /Type0 /DescendantFonts [1] >>",
            "<< /Type /Font /Subtype /Type0 /DescendantFonts [<< >> << >>] >>",
            "<< /Type /Font /Subtype /TrueType /FontDescriptor << >> >>",
            "<< /Type /Font /Subtype /TrueType /FontDescriptor << /FontFile2 5 0 R >> >>",
            "<< /Type /Font /Subtype /TrueType /FontDescriptor << /FontFile2 6 0 R /FontFile3 6 0 R >> >>")
        for (font in fonts) rejected(pdf(page = "/Resources << /Font << /F1 4 0 R >> >>", extra = listOf(font,
            "<< /Length 0 >>\nstream\n\nendstream", "<< /Length 1 >>\nstream\nx\nendstream")))
    }

    @Test fun `decoded file depth and collection budgets reject before publication and preserve cancellation`() {
        val stream = pdf(page = "/Contents 4 0 R", extra = listOf("<< /Length 10 >>\nstream\n1234567890\nendstream"))
        validate(stream, decoded = 10)
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, assertThrows(PdfRenderException::class.java) { validate(stream, decoded = 9) }.code)
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, assertThrows(PdfRenderException::class.java) { validate(pdf(), control = PdfRenderControl(64)) }.code)
        rejected(pdf(catalog = "/Probe " + "[".repeat(150) + "0" + "]".repeat(150)), PdfFailureCode.RESOURCE_LIMIT)
        rejected(pdf(catalog = "/Probe [" + "0 ".repeat(100_001) + "]"), PdfFailureCode.RESOURCE_LIMIT)
        for (box in listOf("0 0 0 842", "0 0 595 0", "0 0 15000 842", "0 0 595 15000")) {
            rejected(pdf(box = box))
        }
        assertThrows(IllegalArgumentException::class.java) { validate(pdf(), decoded = 0) }
        assertThrows(IllegalArgumentException::class.java) { validate(pdf(), version = "2.0") }
        val cancelled = PdfRenderException(PdfFailureCode.CANCELLED, "Synthetic cancellation")
        var checkpoints = 0
        assertSame(cancelled, assertThrows(PdfRenderException::class.java) {
            validate(pdf(), control = PdfRenderControl(checkpoint = Runnable { if (++checkpoints == 4) throw cancelled }))
        })
    }
}

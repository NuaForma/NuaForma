package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.internal.ConversionFiles
import dev.nuaforma.docx.pdf.internal.PdfOptions
import dev.nuaforma.docx.pdf.internal.PdfStreams
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.Executors

class ConversionFilesTest {
    @Test
    @EnabledOnOs(OS.LINUX, disabledReason = "This case checks Linux permissions and symbolic links")
    fun `operation files are private distinct and removed without following links`() {
        val outside = Files.createTempFile("nf-independent-", ".txt")
        try {
            val first = ConversionFiles.create(GenerationContext())
            val second = ConversionFiles.create(GenerationContext())
            try {
                assertNotEquals(first.directory, second.directory)
                assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(first.directory)))
                Files.createDirectory(first.directory.resolve("nested"))
                Files.createSymbolicLink(first.directory.resolve("nested/link"), outside)
                Files.writeString(first.docx, "synthetic")
                Files.setPosixFilePermissions(first.directory.resolve("nested"), emptySet())
                first.close()
                assertFalse(Files.exists(first.directory))
                assertTrue(Files.exists(outside))
                assertTrue(Files.exists(second.directory))
            } finally {
                if (Files.exists(first.directory)) first.close()
                second.close()
            }
        } finally { Files.delete(outside) }
    }

    @Test
    fun `failed output framing version and size do not publish bytes`() {
        ConversionFiles.create(GenerationContext()).use { files ->
            for (content in listOf("%PDF-1.4\nsynthetic\n%%EOF", "%PDF-1.7\nsynthetic truncated")) {
                Files.writeString(files.pdf, content)
                val output = ByteArrayOutputStream()
                assertThrows(GenerationException::class.java) { PdfStreams.publish(files.pdf, output, GenerationContext()) }
                assertEquals(0, output.size())
            }
            Files.writeString(files.pdf, "%PDF-1.7\nsynthetic\n%%EOF\n")
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                PdfStreams.publish(files.pdf, output, GenerationContext(GenerationLimits(maxOutputBytes = 16)))
            }
            assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, error.diagnostics.single().code)
            assertEquals(0, output.size())
        }
    }

    @Test
    fun `cancelled copy does not close caller streams`() {
        val output = object : ByteArrayOutputStream() { var closed = false; override fun close() { closed = true } }
        val error = assertThrows(GenerationException::class.java) {
            PdfStreams.copy(byteArrayOf(1).inputStream(), output, 100, GenerationContext(cancellation = CancellationToken { true }))
        }
        assertEquals(DiagnosticCodes.CANCELLED, error.diagnostics.single().code)
        assertFalse(output.closed)
        assertEquals(0, output.size())
    }

    @Test
    fun `options reject unknown profiles versions and implicit defaults`() {
        assertNull(PdfOptions.read(emptyMap()).paragraphDefaults)
        assertEquals(PdfOptions.ParagraphDefaults(160, 278), PdfOptions.read(mapOf("paragraphDefaults" to mapOf("afterTwips" to 160, "line240ths" to 278))).paragraphDefaults)
        for (options in listOf(mapOf("version" to "2.0"), mapOf("profile" to "pdfa-2b"), mapOf("unknown" to true),
            mapOf("paragraphDefaults" to mapOf("afterTwips" to 1.5)), mapOf("paragraphDefaults" to emptyMap<String, Any>()),
            mapOf("paragraphDefaults" to mapOf("afterTwips" to Long.MAX_VALUE)), mapOf("version" to null))) {
            assertEquals(DiagnosticCodes.UNSUPPORTED_OPTION, assertThrows(GenerationException::class.java) { PdfOptions.read(options) }.diagnostics.single().code)
        }
    }
}

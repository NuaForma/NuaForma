package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.internal.ConversionFiles
import dev.nuaforma.docx.pdf.internal.TemporaryBudget
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class TemporaryBudgetTest {
    @Test fun `logical file sizes enforce the aggregate budget even for sparse files`() {
        ConversionFiles.create(GenerationContext()).use { files ->
            TemporaryBudget.check(files.directory, GenerationContext())
            Files.newByteChannel(files.docx, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.position(512L * 1024 * 1024)
                channel.write(ByteBuffer.wrap(byteArrayOf(1)))
            }
            val error = assertThrows(GenerationException::class.java) { TemporaryBudget.check(files.directory, GenerationContext()) }
            assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, error.diagnostics.single().code)
        }
    }

    @Test fun `monitoring does not follow converter supplied links`() {
        ConversionFiles.create(GenerationContext()).use { files ->
            Files.createSymbolicLink(files.directory.resolve("external"), java.nio.file.Path.of("/etc/passwd"))
            assertEquals(DiagnosticCodes.GENERATION_FAILED, assertThrows(GenerationException::class.java) {
                TemporaryBudget.check(files.directory, GenerationContext())
            }.diagnostics.single().code)
        }
    }
}

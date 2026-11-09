package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class PackageTrashTest {
    private val payload = byteArrayOf(0, -1, 13, 10) + "{NOT_A_TEMPLATE<broken".toByteArray()

    private fun parts() = SyntheticDocx.parts().apply {
        put("[trash]/0000.dat", payload)
        put("[trash]/00Af.dat", payload.reversedArray())
    }

    @Test
    fun `untyped physical trash is ignored without changing logical document parts`() {
        val original = parts()
        val bytes = SyntheticDocx.zip(original)
        val copy = bytes.copyOf()
        val output = ByteArrayOutputStream()
        DocxDocuments.open(bytes.inputStream()).use { it.writeTo(output) }
        val restored = SyntheticDocx.unzip(output.toByteArray())
        assertEquals(original.keys.filterNot { it.startsWith("[trash]/") }.toSet(), restored.keys)
        restored.forEach { (name, value) ->
            if (name.endsWith(".xml") || name.endsWith(".rels")) {
                assertEquals(SyntheticDocx.semantic(original.getValue(name)), SyntheticDocx.semantic(value), name)
            } else assertArrayEquals(original.getValue(name), value, name)
        }
        assertArrayEquals(copy, bytes)
        val generated = ByteArrayOutputStream()
        DocxProvider().generate(GenerationRequest(bytes.inputStream(),
            GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX)),
            mapOf("customer" to mapOf("name" to "Trash ignored"), "reference" to "TRASH247")), generated, GenerationContext())
        assertTrue(SyntheticDocx.unzip(generated.toByteArray()).getValue("word/document.xml").decodeToString().contains("Trash ignored"))
    }

    @Test
    fun `lookalike names and content typed trash do not get the exception`() {
        for (name in listOf("[trash]/000.dat", "[trash]/00000.dat", "[trash]/00G0.dat", "[Trash]/0000.dat", "[trash]/0000.DAT", "word/[trash]/0000.dat")) {
            val source = SyntheticDocx.parts().apply { put(name, payload) }
            val error = assertThrows(GenerationException::class.java) { DocxDocuments.open(SyntheticDocx.zip(source).inputStream()) }
            assertEquals(RejectionReason.A_DOCX_PART_HAS_NO_CONTENT_TYPE, error.diagnostics.single().rejectionReason, name)
        }
        for (declaration in listOf("<Default Extension=\"dat\" ContentType=\"application/octet-stream\"/>",
            "<Default Extension=\"DAT\" ContentType=\"application/octet-stream\"/>",
            "<Override PartName=\"/[trash]/00Af.dat\" ContentType=\"application/octet-stream\"/>")) {
            val source = parts().apply { put("[Content_Types].xml", getValue("[Content_Types].xml").decodeToString().replace("</Types>", "$declaration</Types>").toByteArray()) }
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                DocxProvider().generate(GenerationRequest(SyntheticDocx.zip(source).inputStream(),
                    GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX))), output, GenerationContext())
            }
            assertEquals(DiagnosticCodes.INVALID_TEMPLATE, error.diagnostics.single().code)
            assertEquals("[Content_Types].xml", error.diagnostics.single().part)
            assertEquals(0, output.size())
        }
    }

    @Test
    fun `trash cannot become a relationship target`() {
        for (target in listOf("../[trash]/0000.dat", "../%5Btrash%5D/0000.dat")) {
            val source = parts().apply { put("word/_rels/document.xml.rels", getValue("word/_rels/document.xml.rels").decodeToString()
                .replace("</Relationships>", "<Relationship Id=\"trash\" Type=\"${SyntheticDocx.R}/image\" Target=\"$target\"/></Relationships>").toByteArray()) }
            val error = assertThrows(GenerationException::class.java) { DocxDocuments.open(SyntheticDocx.zip(source).inputStream()) }
            assertEquals(DiagnosticCodes.INVALID_TEMPLATE, error.diagnostics.single().code)
        }
    }

    @Test
    fun `physical trash still counts toward archive byte and entry limits`() {
        val source = parts().apply { put("[trash]/0001.dat", ByteArray(100_000)) }
        val bytes = SyntheticDocx.zip(source)
        val logicalBytes = source.filterKeys { !it.startsWith("[trash]/") }.values.sumOf { it.size }.toLong()
        for (limits in listOf(GenerationLimits(maxExpandedBytes = logicalBytes + 10_000), GenerationLimits(maxArchiveEntries = source.size - 1))) {
            val error = assertThrows(GenerationException::class.java) { DocxDocuments.open(bytes.inputStream(), GenerationContext(limits = limits)) }
            assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, error.diagnostics.single().code)
        }
        val cancelled = assertThrows(GenerationException::class.java) {
            DocxDocuments.open(bytes.inputStream(), GenerationContext(cancellation = CancellationToken { true }))
        }
        assertEquals(DiagnosticCodes.CANCELLED, cancelled.diagnostics.single().code)
        val corrupt = bytes.copyOf()
        var found = false
        for (index in 0 until corrupt.size - 46) {
            if (corrupt[index] == 0x50.toByte() && corrupt[index + 1] == 0x4b.toByte() && corrupt[index + 2] == 1.toByte() && corrupt[index + 3] == 2.toByte()) {
                val size = (corrupt[index + 28].toInt() and 255) or ((corrupt[index + 29].toInt() and 255) shl 8)
                if (corrupt.copyOfRange(index + 46, index + 46 + size).decodeToString() == "[trash]/0000.dat") {
                    corrupt[index + 16] = (corrupt[index + 16].toInt() xor 1).toByte()
                    found = true
                    break
                }
            }
        }
        assertTrue(found)
        val damaged = assertThrows(GenerationException::class.java) { DocxDocuments.open(corrupt.inputStream()) }
        assertEquals(RejectionReason.DOCX_ARCHIVE_INTEGRITY_CHECK_FAILED, damaged.diagnostics.single().rejectionReason)
    }
}

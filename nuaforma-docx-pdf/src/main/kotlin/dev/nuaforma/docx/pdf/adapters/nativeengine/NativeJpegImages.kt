package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.DocxImages

/** Request-local Java SE JPEG decoding into the existing opaque PNG print resources. */
internal class NativeJpegImages(private val context: GenerationContext) {
    private val cache = mutableMapOf<String, ByteArray>()
    private var pixels = 0L
    private var encodedBytes = 0L

    fun decode(bytes: ByteArray, part: String): ByteArray = cache.getOrPut(part) {
        context.checkActive()
        validate(bytes, part)
        val normalized = try { DocxImages.normalize(bytes, context) }
        catch (error: GenerationException) {
            if (error.diagnostics.any { it.code in setOf(DiagnosticCodes.CANCELLED, DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) }) throw error
            invalid(part)
        }
        normalized
    }.also { normalized ->
        context.checkActive()
        resource(normalized.size <= minOf(16_777_216L, context.limits.maxExpandedBytes) - encodedBytes)
        encodedBytes += normalized.size
    }

    private fun validate(bytes: ByteArray, part: String) {
        if (bytes.size !in 4..4_194_304 || byte(bytes, 0) != 255 || byte(bytes, 1) != 216) invalid(part)
        var offset = 2
        var frame = false
        var scan = false
        while (offset < bytes.size) {
            context.checkActive()
            if (byte(bytes, offset++) != 255) invalid(part)
            while (offset < bytes.size && byte(bytes, offset) == 255) offset++
            if (offset >= bytes.size) invalid(part)
            val marker = byte(bytes, offset++)
            if (marker == 217) {
                if (!frame || !scan || offset != bytes.size) invalid(part)
                return
            }
            if (marker in 208..216 || marker == 0 || offset > bytes.size - 2) invalid(part)
            val length = (byte(bytes, offset) shl 8) + byte(bytes, offset + 1)
            if (length < 2 || length > bytes.size - offset) invalid(part)
            if (marker in setOf(192, 193, 194)) {
                if (frame || length < 8 || byte(bytes, offset + 2) != 8) invalid(part)
                val components = byte(bytes, offset + 7)
                if (components !in setOf(1, 3) || length != 8 + components * 3) invalid(part)
                val height = (byte(bytes, offset + 3) shl 8) + byte(bytes, offset + 4)
                val width = (byte(bytes, offset + 5) shl 8) + byte(bytes, offset + 6)
                val count = width.toLong() * height
                resource(width in 1..8192 && height in 1..8192 && count in 1..16_000_000 &&
                    count <= minOf(16_000_000L, context.limits.maxExpandedBytes / 12) - pixels)
                pixels += count
                frame = true
            } else if (marker in 192..207 && marker !in setOf(196, 200, 204)) invalid(part)
            offset += length
            if (marker == 218) {
                if (!frame) invalid(part)
                scan = true
                while (offset < bytes.size - 1) {
                    if (offset % 4096 == 0) context.checkActive()
                    if (byte(bytes, offset) == 255) {
                        val next = byte(bytes, offset + 1)
                        if (next == 0 || next in 208..215) { offset += 2; continue }
                        break
                    }
                    offset++
                }
            }
        }
        invalid(part)
    }

    private fun byte(bytes: ByteArray, offset: Int) = bytes[offset].toInt() and 255

    private fun invalid(part: String): Nothing = throw NativeDocxReadException("Unsupported or malformed JPEG image; expected standalone 8-bit grayscale or RGB JPEG", part)

    private fun resource(valid: Boolean) {
        if (!valid) throw GenerationException(listOf(Diagnostic(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.PDF_CONVERSION_BYTE_BUDGET_WAS_EXCEEDED)))
    }
}

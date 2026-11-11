package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.adapters.imageio.ImageDecoder
import java.time.ZoneOffset
import java.util.Locale

/** Memory-only static PNG/JPEG normalization; no paths, URLs or shared mutable state. */
object DocxImages {

    /** Returns fresh PNG bytes with the decoded dimensions; image metadata is not retained. */
    @JvmStatic
    @JvmOverloads
    @Throws(GenerationException::class)
    fun normalize(bytes: ByteArray, context: GenerationContext = GenerationContext()): ByteArray {
        context.checkActive()
        resource(bytes.size.toLong() in 1..minOf(4_194_304L, context.limits.maxExpandedBytes / 2))
        val source = bytes.copyOf()
        val decoded = ImageDecoder.decode(source, ContentPipeContext(Locale.ROOT, ZoneOffset.UTC, context)) { width, height ->
            val pixels = width.toLong() * height
            resource(width in 1..8192 && height in 1..8192 && pixels in 1..16_000_000 &&
                pixels <= (context.limits.maxExpandedBytes - source.size.toLong() * 2) / 12)
        }
        context.checkActive()
        resource(decoded.bytes.size <= minOf(4_194_304L, context.limits.maxExpandedBytes, context.limits.maxOutputBytes))
        return decoded.bytes
    }

    private fun resource(valid: Boolean) {
        if (!valid) throw GenerationException(listOf(Diagnostic(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.IMAGE_PROCESSING_EXCEEDS_THE_GENERATION_RESOURCE_LIMITS)))
    }
}

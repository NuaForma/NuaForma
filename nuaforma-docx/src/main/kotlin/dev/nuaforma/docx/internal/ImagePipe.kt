package dev.nuaforma.docx.internal

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.adapters.imageio.ImageDecoder
import java.util.Base64
import kotlin.math.roundToLong

/** Created once per generation, never shared between providers or requests. */
internal class ImagePipe(private val generation: GenerationContext) : ContentPipe {
    override val definition = DEFINITION
    private var count = 0
    private var encoded = 0L
    private var pixels = 0L
    private var retained = 0L

    override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult {
        context.checkActive()
        fun maximum(index: Int): Double = (arguments.getOrNull(index) as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it >= 0.1 && it <= 500
        } ?: invalid()
        val maxWidth = maximum(0)
        val maxHeight = maximum(1)
        val keepRatio = arguments.getOrNull(2) as? Boolean ?: invalid()
        if (value == null) return ContentResult.Empty
        val text = value as? String ?: invalid()
        if (text.isEmpty()) invalid()
        if (++count > minOf(128L, generation.limits.maxRepetitions)) limit()
        if (text.length > 8 * 1024 * 1024 || text.length > minOf(32L * 1024 * 1024, generation.limits.maxExpandedBytes) - encoded) limit()
        encoded += text.length
        if (text.length % 4 != 0) invalid()
        // Strict padded RFC 4648 alphabet; no whitespace, data URI, URL-safe variant or external fetch.
        var padding = 0
        text.forEachIndexed { index, char ->
            if (index % 4096 == 0) context.checkActive()
            if (char == '=') { padding++; if (index < text.length - 2 || padding > 2) invalid() }
            else if (padding != 0 || !(char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '+' || char == '/')) invalid()
        }
        if (padding > 0) {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val last = alphabet.indexOf(text[text.length - padding - 1])
            if (last < 0 || (padding == 1 && last and 3 != 0) || (padding == 2 && last and 15 != 0)) invalid()
        }
        val byteCount = text.length.toLong() / 4 * 3 - padding
        if (byteCount > 6L * 1024 * 1024 || byteCount > generation.limits.maxExpandedBytes) limit()
        val bytes = try { Base64.getDecoder().decode(text) } catch (_: IllegalArgumentException) { invalid() }
        val decoded = ImageDecoder.decode(bytes, context) { width, height ->
            val size = width.toLong() * height
            if (width !in 1..8192 || height !in 1..8192 || size > 4_000_000 ||
                size > minOf(16_000_000L, (generation.limits.maxExpandedBytes - encoded) / 12) - pixels) limit()
            pixels += size
        }
        if (decoded.bytes.size > minOf(32L * 1024 * 1024, generation.limits.maxOutputBytes) - retained) limit()
        retained += decoded.bytes.size
        val intrinsicWidth = decoded.width * 25.4 / 96
        val intrinsicHeight = decoded.height * 25.4 / 96
        val x = minOf(1.0, maxWidth / intrinsicWidth)
        val y = minOf(1.0, maxHeight / intrinsicHeight)
        val width = intrinsicWidth * if (keepRatio) minOf(x, y) else x
        val height = intrinsicHeight * if (keepRatio) minOf(x, y) else y
        context.checkActive()
        return ImageContent(decoded.bytes, maxOf(1, (width * 36000).roundToLong()), maxOf(1, (height * 36000).roundToLong()))
    }

    private fun invalid(): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST,
        "Image requires strict Base64 PNG/JPEG data and maximum dimensions from 0.1 to 500 millimetres")
    private fun limit(): Nothing = DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "Image processing exceeds the generation resource limits")

    companion object {
        val DEFINITION = ContentPipeDefinition("image", "Static Base64 PNG/JPEG image; 96 DPI intrinsic size, fit within millimetre maxima, never upscale", listOf(
            OptionDefinition("maxWidthMm", OptionType.NUMBER, "Maximum width in millimetres, 0.1 through 500", defaultValue = 50),
            OptionDefinition("maxHeightMm", OptionType.NUMBER, "Maximum height in millimetres, 0.1 through 500", defaultValue = 50),
            OptionDefinition("keepRatio", OptionType.BOOLEAN, "Preserve aspect ratio; false scales each axis independently without upscaling", defaultValue = true)),
            listOf(ContentPipeSupport(DocumentFormat.DOCX, DocumentFormat.DOCX)), "Null inserts nothing; missing paths require an explicit fallback",
            setOf("image/png", "image/jpeg"))
    }
}

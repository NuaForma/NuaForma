package dev.nuaforma.docx.internal.richtext

import dev.nuaforma.contracts.*

/** Private hand-off: parsing and document mutation stay behind the generator's adapters. */
internal class RichTextContent(val source: String, val trimStart: Boolean, val trimEnd: Boolean,
                               val inline: Boolean, val budget: RichTextBudget) : ContentResult

internal data class RichColor(val red: Int, val green: Int, val blue: Int)
internal data class RichStyle(val bold: Boolean? = null, val italic: Boolean? = null,
                              val underline: Boolean? = null, val strike: Boolean? = null,
                              val family: String? = null, val halfPoints: Int? = null, val color: RichColor? = null)
internal data class RichSpan(val text: String, val style: RichStyle)
internal class RichParagraph(spans: List<RichSpan>) {
    val spans: List<RichSpan> = java.util.List.copyOf(spans)
    // Even ordinary spaces can carry visible underline/background/glyph formatting from the marker.
    val empty: Boolean get() = spans.all { it.text.isEmpty() }
}

/** One budget per generation, shared by all fragments, parts and repeated markers. */
internal class RichTextBudget(private val context: GenerationContext) {
    private var encoded = 0L
    private var decoded = 0L
    private var tokens = 0L
    private var runs = 0
    private var paragraphs = 0
    private var retained = 0L
    val maxDepth: Int get() = minOf(64, context.limits.maxExpressionDepth)

    fun source(value: String) {
        checkActive()
        if (value.length > 1_048_576 || value.length.toLong() > context.limits.maxExpandedBytes) limit()
        var bytes = 0L
        value.forEachIndexed { index, character ->
            if (index and 1023 == 0) checkActive()
            // Conservative UTF-8 accounting also bounds malformed surrogate input before parsing.
            bytes += when { character.code < 128 -> 1; character.code < 2048 -> 2; else -> 3 }
            if (bytes > 1_048_576) limit()
        }
        encoded += bytes
        if (encoded > minOf(4_194_304L, context.limits.maxExpandedBytes)) limit()
    }
    fun token() { if (++tokens > 1_000_000) limit(); if (tokens and 127 == 0L) checkActive() }
    fun character() { if (++decoded > 100_000) limit(); retain(2) }
    fun run() { if (++runs > 16_384) limit(); retain(128) }
    fun paragraph() { if (++paragraphs > 2048) limit(); retain(64) }
    fun retain(bytes: Long) {
        val maximum = minOf(33_554_432L, context.limits.maxExpandedBytes, (encoded + 1024) * 1024)
        if (bytes < 0 || bytes > maximum - retained) limit()
        retained += bytes
    }
    fun checkActive() = context.checkActive()
    fun limit(): Nothing = throw GenerationException(listOf(Diagnostic(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED,
        RejectionReason.RICH_TEXT_EXCEEDS_PROCESSING_LIMITS)))
}

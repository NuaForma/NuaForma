package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*

/** Report which existing conversion limit was exceeded without leaking source data or paths. */
internal enum class NativeConversionBudget(private val description: String, private val unit: String = "bytes") {
    SOURCE_PACKAGE("Source DOCX package"),
    PREPARED_PACKAGE("Prepared DOCX package"),
    SOURCE_EXPANDED_PACKAGE("Expanded source DOCX package"),
    SOURCE_PART_SIZE("Expanded source DOCX package part"),
    EXPANDED_PACKAGE("Expanded prepared DOCX package"),
    CLEANUP_CHANGES("DOCX cleanup changes", "changes"),
    PACKAGE_PART_COUNT("DOCX package parts", "parts"),
    PACKAGE_PART_SIZE("Expanded prepared DOCX package part"),
    OUTPUT_PACKAGE("Diagnostic or cleaned DOCX output"),
    NEEDED_FONT_COUNT("Native PDF font catalog", "fonts"),
    NEEDED_FONT_SIZE("Native PDF font bytes"),
    SUPPLIED_FONT_COUNT("Supplied PDF font resources", "fonts"),
    SUPPLIED_FONT_SIZE("Current supplied PDF font resource"),
    SVG_IMAGES("Rasterized SVG images");

    fun check(context: GenerationContext, required: Long, available: Long) {
        if (required <= available) return
        val diagnostic = Diagnostic(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.PDF_CONVERSION_BYTE_BUDGET_WAS_EXCEEDED)
        if (context.logging.mode != LoggingMode.DEVELOPMENT) throw GenerationException(listOf(diagnostic))
        val detail = "$description exceeds its limit (required at least $required $unit, available ${available.coerceAtLeast(0)} $unit)"
        throw GenerationException(listOf(diagnostic.withSourceContext(DocumentSourceContext(detail, null, null, ""))))
    }
}

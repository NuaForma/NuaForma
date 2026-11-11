package dev.nuaforma.docx.internal

import dev.nuaforma.contracts.*

/** Pure geometry; no fonts, resources, mutable request state or second expression evaluator. */
internal class CheckboxPipe : ContentPipe {
    override val definition = DEFINITION

    override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult {
        context.checkActive()
        if (value !is Boolean || arguments.size != 3 || arguments[0] !is Boolean ||
            arguments.drop(1).any { it !is String || it !in MARKS }) {
            DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.INVALID_CHECKBOX)
        }
        val mark = arguments[if (value) 1 else 2]
        val paths = mutableListOf<VectorPath>()
        val boxed = arguments[0] == true
        if (boxed) paths += path(true, 50 to 50, 950 to 50, 950 to 950, 50 to 950)
        when (mark) {
            "check" -> paths += if (boxed) path(false, 220 to 520, 420 to 740, 800 to 260)
                else path(false, 50 to 500, 360 to 872, 950 to 128)
            "cross" -> {
                val low = if (boxed) 250 else 50
                val high = 1000 - low
                paths += path(false, low to low, high to high)
                paths += path(false, low to high, high to low)
            }
        }
        return if (paths.isEmpty()) ContentResult.Empty else ContentResult.Vector(paths)
    }

    private fun path(closed: Boolean, vararg points: Pair<Int, Int>) =
        VectorPath(points.map { VectorPoint(it.first, it.second) }, closed)

    companion object {
        private val MARKS = listOf("check", "cross", "empty")
        val DEFINITION = ContentPipeDefinition("checkbox", "Static font-independent vector checkbox from a boolean expression", listOf(
            OptionDefinition("withBox", OptionType.BOOLEAN, "Draw a rectangular outline", defaultValue = true),
            OptionDefinition("trueMark", OptionType.STRING, "Mark selected for true", defaultValue = "check", allowedValues = MARKS),
            OptionDefinition("falseMark", OptionType.STRING, "Mark selected for false", defaultValue = "empty", allowedValues = MARKS)),
            listOf(ContentPipeSupport(DocumentFormat.DOCX, DocumentFormat.DOCX)),
            "Requires a boolean; null and missing values fail. Use ?? false for an explicit fallback")
    }
}

package dev.nuaforma.docx.internal

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.richtext.RichTextBudget
import dev.nuaforma.docx.internal.richtext.RichTextContent

/** The language evaluates expressions; this request-private pipe only declares structured insertion. */
internal class RichTextPipe(generation: GenerationContext) : ContentPipe {
    private val budget = RichTextBudget(generation)
    override val definition: ContentPipeDefinition get() = DEFINITION
    override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult {
        context.checkActive()
        if (value != null && value !is String || arguments.size != 4 || arguments[0] != "rtf" || arguments.drop(1).any { it !is Boolean })
            DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.INVALID_RICH_TEXT)
        val source = value ?: ""
        budget.source(source)
        return RichTextContent(source, arguments[1] as Boolean, arguments[2] as Boolean, arguments[3] as Boolean, budget)
    }
    companion object {
        val DEFINITION = ContentPipeDefinition("richText", "Bounded RTF text with inline joining and independent empty-boundary trimming; never evaluated as template instructions", listOf(
            OptionDefinition("format", OptionType.STRING, "Explicit source format; only rtf is supported", required = true, allowedValues = listOf("rtf")),
            OptionDefinition("trimStart", OptionType.BOOLEAN, "Remove empty boundary paragraphs at the start", defaultValue = true),
            OptionDefinition("trimEnd", OptionType.BOOLEAN, "Remove empty boundary paragraphs at the end", defaultValue = true),
            OptionDefinition("inline", OptionType.BOOLEAN, "Join first/last paragraphs to surrounding text; false requires a standalone marker", defaultValue = true)),
            listOf(ContentPipeSupport(DocumentFormat.DOCX, DocumentFormat.DOCX)), "Null, missing and empty strings insert nothing",
            setOf("application/rtf"), true)
    }
}

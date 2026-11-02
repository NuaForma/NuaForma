package dev.nuaforma.contracts

import java.io.InputStream
import java.io.OutputStream

/** Optional development tool. Writes a separate DOCX template without evaluating template instructions. */
interface TemplateCleanupProvider {

    @Throws(GenerationException::class, java.io.IOException::class)
    fun cleanTemplate(template: InputStream, output: OutputStream, context: GenerationContext): TemplateCleanupReport
}

enum class TemplateCleanupAction { REMOVE_PART, REMOVE_ELEMENT, REMOVE_ATTRIBUTE, RESTORE_REQUIRED_STRUCTURE }

/** Private inspection artifact metadata, not an automatic assessment of the document's appearance. */
class TemplateCleanupChange(val action: TemplateCleanupAction, val part: String, val location: String?, val reason: String) {
    init {
        require(part.length in 1..256 && part.none { it.isISOControl() })
        require(location == null || location.length in 1..4096 && location.none { it.isISOControl() })
        require(reason.length in 1..256 && reason.none { it.isISOControl() })
    }
}

class TemplateCleanupReport(changes: List<TemplateCleanupChange>) {
    val changes: List<TemplateCleanupChange> = immutableList(changes)
}

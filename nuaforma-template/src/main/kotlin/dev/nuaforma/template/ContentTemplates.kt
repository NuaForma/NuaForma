package dev.nuaforma.template

import dev.nuaforma.contracts.*
import dev.nuaforma.template.internal.Session
import dev.nuaforma.template.internal.ContentMarkers

/** Additive content-aware facade; existing text-only APIs reject content markers. */
interface ContentTemplateSession : TemplateSession {
    @Throws(GenerationException::class)
    fun expandContent(source: String): List<ContentTemplateFragment>
    @Throws(GenerationException::class)
    fun validateText(source: String)
    @Throws(GenerationException::class)
    override fun branch(): ContentTemplateSession
    @Throws(GenerationException::class)
    override fun repeat(expression: String, alias: String): List<ContentTemplateSession>
}

class ContentTemplateFragment(
    val sourceStart: Int, val sourceEnd: Int, val text: String, val literal: Boolean, val content: ContentResult?,
)

object ContentTemplates {
    /** Detect candidate markers in a non-insertable location; this does not validate or execute them. */
    @JvmStatic
    @JvmOverloads
    @Throws(GenerationException::class)
    fun containsContentMarker(source: String, context: GenerationContext = GenerationContext()): Boolean = ContentMarkers.contains(source, context)

    @JvmStatic
    @JvmOverloads
    @Throws(GenerationException::class)
    fun open(data: Map<String, *>, pipes: List<ContentPipe>, options: GenerationOptions = GenerationOptions(),
             context: GenerationContext = GenerationContext()): ContentTemplateSession = Session.openContent(data, pipes, options, context)
}

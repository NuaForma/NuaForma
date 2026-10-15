package dev.nuaforma.template

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.contracts.GenerationOptions
import dev.nuaforma.template.internal.Session

/** One generation's scopes and cumulative budgets; not safe for concurrent use. */
interface TemplateSession {
    @Throws(GenerationException::class)
    fun expand(source: String): List<TemplateFragment>
    @Throws(GenerationException::class)
    fun test(expression: String): Boolean
    /** Enter a structural condition body without changing its bindings. */
    @Throws(GenerationException::class)
    fun branch(): TemplateSession
    @Throws(GenerationException::class)
    fun repeat(expression: String, alias: String): List<TemplateSession>
    @Throws(GenerationException::class)
    fun validate(source: String)
    @Throws(GenerationException::class)
    fun validateExpression(expression: String)

    companion object {
        @JvmStatic
        @JvmOverloads
        @Throws(GenerationException::class)
        fun open(data: Map<String, *>, options: GenerationOptions = GenerationOptions(), context: GenerationContext = GenerationContext()): TemplateSession {
            context.checkActive()
            return Session.open(data, options, context, TemplatePipes.discover())
        }

        @JvmStatic
        @JvmOverloads
        @Throws(GenerationException::class)
        fun open(data: Map<String, *>, pipes: TemplatePipes, options: GenerationOptions = GenerationOptions(), context: GenerationContext = GenerationContext()): TemplateSession =
            Session.open(data, options, context, pipes)
    }
}

/** Source locations use UTF-16 offsets. Substitutions inherit the opening marker's formatting. */
class TemplateFragment(val sourceStart: Int, val sourceEnd: Int, val text: String, val literal: Boolean)

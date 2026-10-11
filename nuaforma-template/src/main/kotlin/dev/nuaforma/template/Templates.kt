package dev.nuaforma.template

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.template.internal.TemplateParser

/** Compilation does not evaluate data or obtain host resources. */
object Templates {
    @JvmStatic
    @JvmOverloads
    @Throws(GenerationException::class)
    fun compile(source: String, context: GenerationContext = GenerationContext()): CompiledTemplate =
        TemplateParser(source, context).compile()
}

package dev.nuaforma.template

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.contracts.GenerationOptions

/** Immutable, reusable program. Each render snapshots data and owns its evaluation state. */
interface CompiledTemplate {
    @Throws(GenerationException::class)
    fun render(data: Map<String, *>, options: GenerationOptions, context: GenerationContext): String

    @Throws(GenerationException::class)
    fun render(data: Map<String, *>): String = render(data, GenerationOptions(), GenerationContext())
}

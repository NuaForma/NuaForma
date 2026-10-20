package dev.nuaforma.template

import dev.nuaforma.contracts.*
import dev.nuaforma.template.internal.ClasspathPipes
import dev.nuaforma.template.internal.Safety

/** Immutable registration snapshot. Implementations are trusted, shared, thread-safe host code. */
class TemplatePipes private constructor(pipes: List<TemplatePipe>) {
    internal val registrations: Map<String, TemplatePipe>
    init {
        if (pipes.size > 128) Safety.limit()
        val captured = pipes.map { pipe ->
            val definition = try { requireNotNull(pipe.definition) } catch (_: Exception) { invalid() }
                catch (_: LinkageError) { invalid() }
            object : TemplatePipe {
                override val definition = definition
                override fun apply(value: Any?, arguments: List<Any?>, context: TemplatePipeContext): Any? = pipe.apply(value, arguments, context)
            }
        }
        if (captured.map { it.definition.name }.distinct().size != captured.size) invalid()
        registrations = java.util.Collections.unmodifiableMap(captured.sortedBy { it.definition.name }.associateBy { it.definition.name })
    }
    fun definitions(): List<TemplatePipeDefinition> = java.util.Collections.unmodifiableList(registrations.values.map { it.definition })
    companion object {
        @JvmStatic
        fun of(pipes: List<TemplatePipe>): TemplatePipes = TemplatePipes(pipes)
        @JvmStatic
        @JvmOverloads
        fun discover(loader: ClassLoader = Thread.currentThread().contextClassLoader ?: TemplatePipes::class.java.classLoader): TemplatePipes =
            TemplatePipes(ClasspathPipes(loader).discover())
        private fun invalid(): Nothing = Safety.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.VALUE_PIPE_REGISTRATIONS_MUST_HAVE_VALID_UNIQUE_DEFINITIONS)
    }
}

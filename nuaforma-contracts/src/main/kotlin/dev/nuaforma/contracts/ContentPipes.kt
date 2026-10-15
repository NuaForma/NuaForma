package dev.nuaforma.contracts

import java.time.ZoneId
import java.util.Locale

/** Optional provider facet. Declarations belong to this provider, never a global pipe registry. */
interface ContentPipeProvider {
    fun contentPipes(): List<ContentPipeDefinition>
}

enum class ContentInsertionContext { INLINE }

class ContentPipeSupport @JvmOverloads constructor(
    val input: DocumentFormat,
    val output: DocumentFormat,
    profiles: Set<String> = emptySet(),
) {
    val profiles: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(profiles))
    init { require(this.profiles.all { it.matches(Regex("[a-z0-9][a-z0-9.-]{0,63}")) }) }
}

/** Arguments are positional expressions; definitions document and constrain their public types. */
class ContentPipeDefinition @JvmOverloads constructor(
    val name: String,
    val description: String,
    arguments: List<OptionDefinition> = emptyList(),
    support: List<ContentPipeSupport> = emptyList(),
    val nullBehavior: String = "Passed to the implementation",
) {
    val arguments: List<OptionDefinition> = immutableList(arguments)
    val support: List<ContentPipeSupport> = immutableList(support)
    val chainable: Boolean get() = false
    val contexts: Set<ContentInsertionContext> = setOf(ContentInsertionContext.INLINE)
    init {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) && name !in setOf("number", "date"))
        require(description.isNotBlank() && description.length <= 1024)
        require(nullBehavior.isNotBlank() && nullBehavior.length <= 256)
        require(this.arguments.size <= 16 && this.arguments.map { it.name }.distinct().size == this.arguments.size)
        require(this.support.size <= 128)
        require(this.arguments.dropWhile { it.required }.none { it.required })
    }
}

/** Trusted generator extension. Implementations must be thread-safe and cooperate with cancellation. */
interface ContentPipe {
    val definition: ContentPipeDefinition
    @Throws(GenerationException::class)
    fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult
}

/** Only the evaluated value and arguments are supplied; no request, resources or other data. */
class ContentPipeContext(val locale: Locale, val timeZone: ZoneId, private val generation: GenerationContext) {
    @Throws(GenerationException::class)
    fun checkActive() = generation.checkActive()
}

/** Trusted generator-owned structured content, never an implicit JSON value or host-object escape. */
interface ContentResult {
    class Text(val text: String) : ContentResult
    object Empty : ContentResult
}

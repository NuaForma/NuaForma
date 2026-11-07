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
    private var declaredContentTypes: Set<String> = emptySet()
    private var declaredMissingAsNull = false
    /** Explicit opt-in; ordinary placeholders and existing content pipes keep strict missing-value behavior. */
    val missingAsNull: Boolean get() = declaredMissingAsNull
    /** Accepted source media types, not a generic document representation or MIME-based dispatch. */
    val contentTypes: Set<String> get() = declaredContentTypes

    /** Preserve the original primary/default-argument constructor descriptors. */
    constructor(name: String, description: String, arguments: List<OptionDefinition>, support: List<ContentPipeSupport>,
                nullBehavior: String, contentTypes: Set<String>) : this(name, description, arguments, support, nullBehavior) {
        require(contentTypes.size <= 32 && contentTypes.all {
            it.length <= 127 && it.matches(Regex("[a-z][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*"))
        })
        declaredContentTypes = java.util.Collections.unmodifiableSet(LinkedHashSet(contentTypes))
    }
    constructor(name: String, description: String, arguments: List<OptionDefinition>, support: List<ContentPipeSupport>,
                nullBehavior: String, contentTypes: Set<String>, missingAsNull: Boolean) :
        this(name, description, arguments, support, nullBehavior, contentTypes) {
        declaredMissingAsNull = missingAsNull
    }
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

    /** Opaque round-cap/join polylines in a 1000-unit view box; adapters inherit size, color and bounded text weight. */
    class Vector @JvmOverloads constructor(paths: List<VectorPath>, val strokeWidth: Int = 70) : ContentResult {
        val paths: List<VectorPath> = java.util.Collections.unmodifiableList(ArrayList(paths))

        init {
            require(strokeWidth in 10..100) { "Invalid vector stroke width" }
            require(this.paths.size in 1..8) { "A vector needs 1 to 8 paths" }
            require(this.paths.sumOf { it.points.size } <= 64) { "Vector point budget exceeded" }
            val margin = (strokeWidth + 1) / 2
            require(this.paths.all { path -> path.points.all {
                it.x in margin..1000 - margin && it.y in margin..1000 - margin
            } }) { "Vector stroke exceeds its view box" }
        }
    }
}

/** Integer coordinates avoid non-finite values and ambiguous precision at adapter boundaries. */
data class VectorPoint(val x: Int, val y: Int) {
    init {
        require(x in 0..1000 && y in 0..1000) { "Invalid vector point" }
    }
}

/** One open or closed stroked polyline, without fills, curves, resources or executable content. */
class VectorPath @JvmOverloads constructor(points: List<VectorPoint>, val closed: Boolean = false) {
    val points: List<VectorPoint> = java.util.Collections.unmodifiableList(ArrayList(points))

    init {
        require(this.points.size in (if (closed) 3 else 2)..16) { "Invalid vector path length" }
        require(this.points.zipWithNext().all { (a, b) -> a != b }) { "Degenerate vector segment" }
        require(!closed || this.points.first() != this.points.last()) { "Closed paths close implicitly" }
    }
}

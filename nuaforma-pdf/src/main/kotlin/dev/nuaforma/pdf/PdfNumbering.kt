package dev.nuaforma.pdf

import java.util.Collections
import java.util.Locale

enum class PdfNumberFormat { DECIMAL, DECIMAL_ZERO, LOWER_LETTER, UPPER_LETTER, LOWER_ROMAN, UPPER_ROMAN, BULLET }
enum class PdfListSuffix { TAB, SPACE, NOTHING }

/** Neutral sequence rules. Null restart means the previous level; -1 means never restart. */
data class PdfListLevel @JvmOverloads constructor(
    val format: PdfNumberFormat, val pattern: String, val start: Int = 1,
    val restartAfter: Int? = null, val legal: Boolean = false,
) {
    init {
        require(pattern.isNotEmpty() && pattern.length <= 128) { "Invalid list pattern" }
        require(start in 0..1_000_000 && (restartAfter == null || restartAfter in -1..7)) { "Invalid list counter policy" }
    }
}

class PdfListDefinition @JvmOverloads constructor(val id: String, levels: List<PdfListLevel>, val sequenceId: String = id) {
    val levels: List<PdfListLevel> = Collections.unmodifiableList(ArrayList(levels))
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && this.levels.size in 1..9) { "Invalid list definition" }
        require(sequenceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid numbering sequence" }
        require(this.levels.withIndex().all { (index, level) -> level.restartAfter == null || level.restartAfter < index }) { "Invalid restart ancestor" }
    }
}
data class PdfListStep @JvmOverloads constructor(val instanceId: String, val level: Int, val restart: Boolean = false)
data class PdfListValue(val instanceId: String, val level: Int, val ordinal: Int?, val text: String) {
    init {
        require(instanceId.matches(Regex("[A-Za-z0-9_-]{1,64}")) && level in 0..8 && (ordinal == null || ordinal in 0..1_000_000)) { "Invalid list value" }
        require(text.isNotEmpty() && text.length <= 256) { "Invalid list label" }
    }
}

/** Resolved label with semantic identity, separate from body text and its continuation indent. */
data class PdfListLabel @JvmOverloads constructor(
    val value: PdfListValue, val style: PdfTextStyle,
    val alignment: PdfAlignment = PdfAlignment.LEFT, val suffix: PdfListSuffix = PdfListSuffix.TAB,
    val tabStop: Float? = null,
    val defaultTabInterval: Float = 36f,
) {
    init {
        require(tabStop == null || tabStop.isFinite() && tabStop in 0f..14_400f) { "Invalid list tab stop" }
        require(defaultTabInterval.isFinite() && defaultTabInterval in 1f..14_400f) { "Invalid default list tab interval" }
    }
}

/** Pure, bounded batch resolution; every call owns its counters. Readers supply source order. */
object PdfNumbering {
    @JvmStatic fun resolve(definitions: List<PdfListDefinition>, steps: List<PdfListStep>): List<PdfListValue> {
        require(definitions.size <= 128 && steps.size <= 2_000) { "Numbering budget exceeded" }
        val byId = definitions.associateBy { it.id }
        require(byId.size == definitions.size) { "Duplicate list instance" }
        require(definitions.groupBy { it.sequenceId }.values.all { group -> group.map { it.levels.size }.distinct().size == 1 }) { "Incompatible shared sequence levels" }
        val counters = mutableMapOf<String, IntArray>()
        var characters = 0
        return Collections.unmodifiableList(steps.map { step ->
            if (Thread.currentThread().isInterrupted) throw PdfRenderException(PdfFailureCode.CANCELLED, "Numbering cancelled")
            val definition = requireNotNull(byId[step.instanceId]) { "Unknown list instance" }
            require(step.level in definition.levels.indices) { "Unknown list level" }
            val rule = definition.levels[step.level]
            val state = counters.getOrPut(definition.sequenceId) { IntArray(definition.levels.size) { -1 } }
            if (rule.format != PdfNumberFormat.BULLET) require((0 until step.level).all { state[it] >= 0 }) { "Skipped numbering ancestors are not qualified" }
            state[step.level] = if (step.restart || state[step.level] < 0) rule.start else state[step.level] + 1
            require(state[step.level] <= 1_000_000) { "List counter exceeded" }
            for (index in step.level + 1 until state.size) {
                val ancestor = definition.levels[index].restartAfter ?: (index - 1)
                if (ancestor >= step.level) state[index] = -1
            }
            val text = if (rule.format == PdfNumberFormat.BULLET) {
                require('%' !in rule.pattern) { "Bullet placeholders are unsupported" }; rule.pattern
            } else Regex("%([1-9])").replace(rule.pattern) { match ->
                val index = match.groupValues[1].toInt() - 1
                require(index <= step.level && index in state.indices && state[index] >= 0) { "Unresolved list placeholder" }
                format(state[index], if (rule.legal) PdfNumberFormat.DECIMAL else definition.levels[index].format)
            }.also { require('%' !in it) { "Invalid list placeholder" } }
            characters += text.length
            require(characters <= 100_000) { "List label budget exceeded" }
            PdfListValue(step.instanceId, step.level, state[step.level].takeUnless { rule.format == PdfNumberFormat.BULLET }, text)
        })
    }

    private fun format(value: Int, format: PdfNumberFormat): String = when (format) {
        PdfNumberFormat.DECIMAL -> value.toString()
        PdfNumberFormat.DECIMAL_ZERO -> value.toString().padStart(2, '0')
        PdfNumberFormat.LOWER_LETTER, PdfNumberFormat.UPPER_LETTER -> {
            require(value > 0) { "Alphabetic numbering requires a positive value" }
            val repetitions = (value - 1) / 26 + 1
            require(repetitions <= 256) { "Alphabetic list label budget exceeded" }
            ('a'.code + (value - 1) % 26).toChar().toString().repeat(repetitions)
                .let { if (format == PdfNumberFormat.UPPER_LETTER) it.uppercase(Locale.ROOT) else it }
        }
        PdfNumberFormat.LOWER_ROMAN, PdfNumberFormat.UPPER_ROMAN -> {
            require(value in 1..3999) { "Roman numbering is outside the qualified range" }
            var n = value; val result = StringBuilder()
            for ((amount, symbol) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
                while (n >= amount) { result.append(symbol); n -= amount }
            }
            result.toString().let { if (format == PdfNumberFormat.LOWER_ROMAN) it.lowercase(Locale.ROOT) else it }
        }
        PdfNumberFormat.BULLET -> throw IllegalArgumentException("Bullet ancestors cannot form numeric labels")
    }
}

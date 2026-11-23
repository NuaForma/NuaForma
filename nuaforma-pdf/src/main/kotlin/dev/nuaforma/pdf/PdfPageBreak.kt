package dev.nuaforma.pdf

import java.util.Collections

/** In-paragraph hard page break. Optional destinations identify the caret before the break. */
class PdfPageBreak private constructor(val style: PdfTextStyle, destinations: List<PdfDestination>) : PdfInline {
    constructor(style: PdfTextStyle) : this(style, emptyList())

    val destinations: List<PdfDestination> = Collections.unmodifiableList(ArrayList(destinations))

    init {
        require(this.destinations.size <= 32 && this.destinations.map { it.name }.distinct().size == this.destinations.size) {
            "Invalid page break destinations"
        }
    }

    fun withDestinations(destinations: List<PdfDestination>): PdfPageBreak = PdfPageBreak(style, destinations)

    operator fun component1(): PdfTextStyle = style

    fun copy(style: PdfTextStyle = this.style): PdfPageBreak = PdfPageBreak(style, destinations)

    override fun equals(other: Any?): Boolean =
        other is PdfPageBreak && other.style == style && other.destinations == destinations

    override fun hashCode(): Int = 31 * style.hashCode() + destinations.hashCode()

    override fun toString(): String = "PdfPageBreak(style=$style, destinations=$destinations)"
}

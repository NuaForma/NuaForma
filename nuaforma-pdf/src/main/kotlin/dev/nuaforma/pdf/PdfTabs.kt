package dev.nuaforma.pdf

import java.util.Collections

enum class PdfTabAlignment { LEFT, RIGHT, CENTER, DECIMAL }
enum class PdfTabLeader { NONE, DOT, HYPHEN, UNDERSCORE }
data class PdfTabStop @JvmOverloads constructor(val position: Float, val alignment: PdfTabAlignment = PdfTabAlignment.LEFT, val leader: PdfTabLeader = PdfTabLeader.NONE) {
    init { require(position.isFinite() && position in 0f..14_400f) { "Invalid tab position" } }
}
class PdfTabSettings @JvmOverloads constructor(stops: List<PdfTabStop> = emptyList(), val defaultInterval: Float = 36f, val decimalSeparator: String = ".") {
    val stops: List<PdfTabStop> = Collections.unmodifiableList(stops.sortedBy { it.position })
    init {
        require(stops.size <= 32 && stops.map { it.position }.toSet().size == stops.size) { "Invalid tab stop count or duplicates" }
        require(defaultInterval.isFinite() && defaultInterval in 1f..14_400f && decimalSeparator in setOf(".", ",")) { "Invalid tab defaults" }
    }
}
/** A typed layout control, never a space inserted into the source text. */
data class PdfTab(val style: PdfTextStyle) : PdfInline

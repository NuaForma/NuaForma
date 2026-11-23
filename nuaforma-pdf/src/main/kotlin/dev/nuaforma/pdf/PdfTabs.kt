package dev.nuaforma.pdf

import java.util.Collections

enum class PdfTabAlignment { LEFT, RIGHT, CENTER, DECIMAL }
enum class PdfTabLeader { NONE, DOT, HYPHEN, UNDERSCORE }

/** FIRST_NUMBER also aligns at the end of the first numeric field, excluding grouping separators. */
enum class PdfDecimalTabPolicy { SEPARATOR, FIRST_NUMBER }
data class PdfTabStop @JvmOverloads constructor(val position: Float, val alignment: PdfTabAlignment = PdfTabAlignment.LEFT, val leader: PdfTabLeader = PdfTabLeader.NONE) {
    init { require(position.isFinite() && position in 0f..14_400f) { "Invalid tab position" } }
}
class PdfTabSettings private constructor(stops: List<PdfTabStop>, val defaultInterval: Float,
    val decimalSeparator: String, options: DecimalOptions) {
    private class DecimalOptions(val policy: PdfDecimalTabPolicy, val grouping: String?)

    @JvmOverloads constructor(stops: List<PdfTabStop> = emptyList(), defaultInterval: Float = 36f, decimalSeparator: String = ".") :
        this(stops, defaultInterval, decimalSeparator, DecimalOptions(PdfDecimalTabPolicy.SEPARATOR, null))

    val decimalPolicy: PdfDecimalTabPolicy = options.policy
    val groupingSeparator: String? = options.grouping

    @JvmOverloads
    fun withDecimalPolicy(policy: PdfDecimalTabPolicy, groupingSeparator: String? = this.groupingSeparator): PdfTabSettings =
        PdfTabSettings(stops, defaultInterval, decimalSeparator, DecimalOptions(policy, groupingSeparator))

    val stops: List<PdfTabStop> = Collections.unmodifiableList(stops.sortedBy { it.position })
    init {
        require(stops.size <= 32 && stops.map { it.position }.toSet().size == stops.size) { "Invalid tab stop count or duplicates" }
        require(defaultInterval.isFinite() && defaultInterval in 1f..14_400f && decimalSeparator in setOf(".", ",")) { "Invalid tab defaults" }
        require(groupingSeparator == null || groupingSeparator.codePointCount(0, groupingSeparator.length) == 1 &&
            groupingSeparator != decimalSeparator && !Character.isDigit(groupingSeparator.codePointAt(0)) &&
            !Character.isISOControl(groupingSeparator.codePointAt(0))) { "Invalid numeric grouping separator" }
    }
}
/** A typed layout control, never a space inserted into the source text. */
class PdfTab private constructor(val style: PdfTextStyle, val navigation: PdfTextNavigation?) : PdfInline {
    constructor(style: PdfTextStyle) : this(style, null)

    init {
        require(navigation == null || navigation.link != null && navigation.destinations.isEmpty()) {
            "A tab can carry a link but cannot own destinations"
        }
    }

    fun withNavigation(value: PdfTextNavigation?): PdfTab = PdfTab(style, value)

    fun copy(style: PdfTextStyle = this.style): PdfTab = PdfTab(style, navigation)

    operator fun component1(): PdfTextStyle = style

    override fun equals(other: Any?): Boolean = other is PdfTab && style == other.style && navigation == other.navigation

    override fun hashCode(): Int = if (navigation == null) style.hashCode() else 31 * style.hashCode() + navigation.hashCode()

    override fun toString(): String = "PdfTab(style=$style)"
}

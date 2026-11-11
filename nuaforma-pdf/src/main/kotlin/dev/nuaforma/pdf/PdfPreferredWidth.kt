package dev.nuaforma.pdf

/** A preferred physical width or percentage of the containing width; layout may override it. */
class PdfPreferredWidth private constructor(val points: Float?, val percent: Float?) {

    override fun equals(other: Any?): Boolean = other is PdfPreferredWidth && points == other.points && percent == other.percent

    override fun hashCode(): Int = 31 * (points?.hashCode() ?: 0) + (percent?.hashCode() ?: 0)

    override fun toString(): String = points?.let { "PdfPreferredWidth.points($it)" } ?: "PdfPreferredWidth.percent($percent)"

    companion object {

        @JvmStatic
        fun points(value: Float): PdfPreferredWidth {
            require(value.isFinite() && value in 0f..14_400f) { "Invalid preferred physical width" }
            return PdfPreferredWidth(if (value == 0f) 0f else value, null)
        }

        @JvmStatic
        fun percent(value: Float): PdfPreferredWidth {
            require(value.isFinite() && value in 0f..100f) { "Invalid preferred percentage width" }
            return PdfPreferredWidth(null, if (value == 0f) 0f else value)
        }

    }
}

package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.PdfDecimalTabPolicy
import dev.nuaforma.pdf.PdfTabSettings

/** Return a UTF-16 prefix boundary; shaping and measurement retain the original spans. */
internal object DecimalTabs {

    fun prefixLength(value: String, settings: PdfTabSettings): Int {
        if (settings.decimalPolicy == PdfDecimalTabPolicy.SEPARATOR)
            return value.indexOf(settings.decimalSeparator).takeIf { it >= 0 } ?: value.length
        val decimal = settings.decimalSeparator.codePointAt(0)
        val grouping = settings.groupingSeparator?.codePointAt(0)
        var numeric = false
        var offset = 0
        while (offset < value.length) {
            val point = value.codePointAt(offset)
            if (point == decimal) return offset
            if (Character.isDigit(point)) numeric = true
            else if (numeric && point != grouping) return offset
            offset += Character.charCount(point)
        }
        return value.length
    }

}

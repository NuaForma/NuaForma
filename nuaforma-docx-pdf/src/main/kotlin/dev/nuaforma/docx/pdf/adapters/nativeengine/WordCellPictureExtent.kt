package dev.nuaforma.docx.pdf.adapters.nativeengine

import kotlin.math.abs

/** Word places small uniformly scaled cell pictures in the outer DrawingML extents. */
internal object WordCellPictureExtent {
    fun isUniform(outerWidth: String?, outerHeight: String?, innerWidth: String?, innerHeight: String?): Boolean {
        val (outerX, outerY, innerX, innerY) = listOf(outerWidth, outerHeight, innerWidth, innerHeight).map {
            it?.toLongOrNull()?.takeIf { size -> size in 1..Int.MAX_VALUE.toLong() } ?: return false
        }
        if (abs(innerX - outerX) > 12_700L || abs(innerY - outerY) > 12_700L) return false
        return abs(innerX.toDouble() / outerX - innerY.toDouble() / outerY) <= .00005
    }
}

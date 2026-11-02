package dev.nuaforma.docx.pdf.adapters.nativeengine.emf

import java.awt.geom.Point2D
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class EmfFailure(message: String) : IllegalArgumentException("EMF: $message")

/** Every offset is relative to this one bounded record, never a file or external resource. */
internal class EmfRecord(val type: Int, val offset: Int, val bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun require(valid: Boolean, message: String) {
        if (!valid) throw EmfFailure("$message (record $type at $offset)")
    }

    fun span(start: Int, length: Int): ByteArray {
        require(start >= 0 && length >= 0 && start.toLong() + length <= bytes.size, "Invalid record range")
        return bytes.copyOfRange(start, start + length)
    }

    fun int(at: Int): Int {
        require(at >= 0 && at.toLong() + 4 <= bytes.size, "Truncated integer")
        return buffer.getInt(at)
    }

    fun short(at: Int): Int {
        require(at >= 0 && at.toLong() + 2 <= bytes.size, "Truncated coordinate")
        return buffer.getShort(at).toInt()
    }

    fun float(at: Int): Double {
        val number = java.lang.Float.intBitsToFloat(int(at)).toDouble()
        require(number.isFinite() && kotlin.math.abs(number) <= 1_000_000, "Invalid transform")
        return number
    }

    fun point(at: Int, small: Boolean = false): Point2D.Double {
        val point = if (small) Point2D.Double(short(at).toDouble(), short(at + 2).toDouble())
            else Point2D.Double(int(at).toDouble(), int(at + 4).toDouble())
        require(kotlin.math.abs(point.x) <= 1_000_000 && kotlin.math.abs(point.y) <= 1_000_000, "Unbounded coordinate")
        return point
    }
}

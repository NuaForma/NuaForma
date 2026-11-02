package dev.nuaforma.docx.pdf.adapters.nativeengine.emf

import dev.nuaforma.contracts.*
import java.awt.Font
import java.security.MessageDigest
import kotlin.math.ceil

/** One translation owns its cache and cumulative raster budget, including repeated sizes of the same image. */
internal class NativeEmfImages(private val context: GenerationContext,
    private val fonts: ((String, Boolean, Boolean) -> Font)?, private val inspect: Boolean) {
    private val decoded = mutableMapOf<String, ByteArray>()
    private var pixels = 0L
    private var bytes = 0L

    fun decode(source: ByteArray, width: Float, height: Float): ByteArray {
        val key = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) } + ":$width:$height"
        return decoded.getOrPut(key) {
            context.checkActive()
            pixels += ceil(width.toDouble() * 4).toLong() * ceil(height.toDouble() * 4).toLong()
            resource(pixels in 1..4_194_304 && pixels * 4 <= context.limits.maxExpandedBytes)
            val output = EmfPicture(context, fonts).decode(source, width, height, inspect) ?: source
            bytes += output.size
            resource(bytes <= minOf(16_777_216L, context.limits.maxExpandedBytes))
            output
        }
    }

    private fun resource(valid: Boolean) {
        if (!valid) throw GenerationException(listOf(Diagnostic(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.PDF_CONVERSION_BYTE_BUDGET_WAS_EXCEEDED)))
    }
}

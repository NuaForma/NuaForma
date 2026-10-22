package dev.nuaforma.docx.adapters.imageio

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.DocxFailure
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/** Memory-only Java SE codec facade. Never changes global ImageIO state or accepts paths/URLs. */
internal object ImageDecoder {
    class Decoded(val bytes: ByteArray, val width: Int, val height: Int)

    fun decode(bytes: ByteArray, context: ContentPipeContext, reserve: (Int, Int) -> Unit): Decoded {
        try {
            val format = if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG)) "png"
                else if (bytes.size >= 4 && bytes[0] == (-1).toByte() && bytes[1] == 0xd8.toByte()) "jpeg" else invalid()
            if (format == "png") validatePng(bytes, context) else validateJpeg(bytes, context)
            val reader = ImageIO.getImageReadersByFormatName(format).asSequence().firstOrNull {
                it.javaClass.module.name == "java.desktop"
            } ?: invalid()
            try {
                val checkedInput = object : MemoryCacheImageInputStream(bytes.inputStream()) {
                    override fun read(): Int { context.checkActive(); return super.read() }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        context.checkActive(); return super.read(buffer, offset, length)
                    }
                }
                checkedInput.use { input ->
                    reader.setInput(input, true, true)
                    reader.addIIOReadWarningListener { _, _ -> invalid() }
                    val width = reader.getWidth(0); val height = reader.getHeight(0)
                    reserve(width, height)
                    context.checkActive()
                    val image = reader.read(0) ?: invalid()
                    try {
                        context.checkActive()
                        if (image.width != width || image.height != height) invalid()
                        // Strip metadata, EXIF orientation, profiles and ancillary compressed chunks.
                        val normalized = BufferedImage(width, height,
                            if (image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
                        try {
                            val row = IntArray(width)
                            for (y in 0 until height) {
                                context.checkActive()
                                image.getRGB(0, y, width, 1, row, 0, width)
                                normalized.setRGB(0, y, width, 1, row, 0, width)
                            }
                            val writer = ImageIO.getImageWritersByFormatName("png").asSequence().firstOrNull {
                                it.javaClass.module.name == "java.desktop"
                            } ?: invalid()
                            try {
                                val output = ByteArrayOutputStream()
                                val bounded = object : OutputStream() {
                                    override fun write(value: Int) { check(1); output.write(value) }
                                    override fun write(value: ByteArray, offset: Int, length: Int) { check(length); output.write(value, offset, length) }
                                    private fun check(length: Int) {
                                        context.checkActive()
                                        if (length > 20 * 1024 * 1024 - output.size()) limit()
                                    }
                                }
                                val checkedOutput = object : MemoryCacheImageOutputStream(bounded) {
                                    override fun write(value: Int) { context.checkActive(); super.write(value) }
                                    override fun write(value: ByteArray, offset: Int, length: Int) {
                                        context.checkActive(); super.write(value, offset, length)
                                    }
                                }
                                checkedOutput.use { stream ->
                                    writer.output = stream
                                    writer.write(null, IIOImage(normalized, null, null), writer.defaultWriteParam)
                                }
                                context.checkActive()
                                return Decoded(output.toByteArray(), width, height)
                            } finally { writer.dispose() }
                        } finally { normalized.flush() }
                    } finally { image.flush() }
                }
            } finally { reader.dispose() }
        } catch (error: GenerationException) { throw error }
        catch (_: Exception) { context.checkActive(); invalid() }
    }

    private fun validatePng(bytes: ByteArray, context: ContentPipeContext) {
        var offset = 8; var header = false; var data = false
        while (offset <= bytes.size - 12) {
            context.checkActive()
            val length = ByteBuffer.wrap(bytes, offset, 4).int
            if (length < 0 || length > bytes.size - offset - 12) invalid()
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (!header && (type != "IHDR" || length != 13)) invalid()
            if (type in setOf("acTL", "fcTL", "fdAT", "iCCP", "zTXt", "iTXt")) invalid()
            val crc = CRC32().apply { update(bytes, offset + 4, length + 4) }.value
            if (crc != ByteBuffer.wrap(bytes, offset + 8 + length, 4).int.toLong().and(0xffffffffL)) invalid()
            if (type == "IHDR") { if (header) invalid(); header = true }
            if (type == "IDAT") data = true
            if (type == "IEND") {
                if (length != 0 || offset + 12 != bytes.size || !data) invalid()
                return
            }
            offset += length + 12
        }
        invalid()
    }

    private fun validateJpeg(bytes: ByteArray, context: ContentPipeContext) {
        var offset = 2; var frame = false; var scan = false
        while (offset < bytes.size) {
            context.checkActive()
            if (bytes[offset++].toInt().and(255) != 255) invalid()
            while (offset < bytes.size && bytes[offset].toInt().and(255) == 255) offset++
            if (offset >= bytes.size) invalid()
            val marker = bytes[offset++].toInt().and(255)
            if (marker == 0xd9) { if (!frame || !scan || offset != bytes.size) invalid(); return }
            if (marker in 0xd0..0xd8 || marker == 0 || offset > bytes.size - 2) invalid()
            val length = (bytes[offset].toInt().and(255) shl 8) + bytes[offset + 1].toInt().and(255)
            if (length < 2 || length > bytes.size - offset) invalid()
            if (marker in setOf(0xc0, 0xc1, 0xc2)) { if (frame) invalid(); frame = true }
            else if (marker in 0xc0..0xcf && marker !in setOf(0xc4, 0xc8, 0xcc)) invalid()
            offset += length
            if (marker == 0xda) {
                scan = true
                while (offset < bytes.size - 1) {
                    if (offset % 4096 == 0) context.checkActive()
                    if (bytes[offset].toInt().and(255) == 255) {
                        val next = bytes[offset + 1].toInt().and(255)
                        if (next == 0 || next in 0xd0..0xd7) { offset += 2; continue }
                        break
                    }
                    offset++
                }
            }
        }
        invalid()
    }

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)
    private fun invalid(): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.IMAGE_MUST_BE_A_VALID_STATIC_PNG_OR_JPEG)
    private fun limit(): Nothing = DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.IMAGE_ENCODING_EXCEEDS_ITS_BYTE_LIMIT)
}

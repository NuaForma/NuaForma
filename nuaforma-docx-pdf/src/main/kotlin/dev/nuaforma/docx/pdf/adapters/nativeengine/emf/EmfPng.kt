package dev.nuaforma.docx.pdf.adapters.nativeengine.emf

import dev.nuaforma.contracts.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Minimal bounded RGBA PNG transport, without ImageIO plugins, disk caches or additional public APIs. */
internal object EmfPng {

    fun encode(image: BufferedImage, context: GenerationContext): ByteArray {
        val limit = minOf(4_194_304L, context.limits.maxExpandedBytes) - 57
        val compressed = ByteArrayOutputStream()
        val bounded = object : OutputStream() {

            override fun write(value: Int) {
                checkSize(1)
                compressed.write(value)
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                checkSize(length)
                compressed.write(bytes, offset, length)
            }

            private fun checkSize(count: Int) {
                context.checkActive()
                if (compressed.size().toLong() + count > limit) throw GenerationException(listOf(
                    Diagnostic(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.PDF_CONVERSION_BYTE_BUDGET_WAS_EXCEEDED)))
            }
        }
        DeflaterOutputStream(bounded).use { output ->
            val row = ByteArray(1 + image.width * 4)
            for (y in 0 until image.height) {
                context.checkActive()
                for (x in 0 until image.width) {
                    val pixel = image.getRGB(x, y)
                    val at = 1 + x * 4
                    row[at] = (pixel ushr 16).toByte()
                    row[at + 1] = (pixel ushr 8).toByte()
                    row[at + 2] = pixel.toByte()
                    row[at + 3] = (pixel ushr 24).toByte()
                }
                output.write(row)
            }
        }
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { output ->
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val header = ByteBuffer.allocate(13).putInt(image.width).putInt(image.height)
                .put(8).put(6).put(0).put(0).put(0).array()
            chunk(output, "IHDR", header)
            chunk(output, "IDAT", compressed.toByteArray())
            chunk(output, "IEND", byteArrayOf())
        } }.toByteArray()
    }

    private fun chunk(output: DataOutputStream, name: String, bytes: ByteArray) {
        val type = name.toByteArray(Charsets.US_ASCII)
        output.writeInt(bytes.size)
        output.write(type)
        output.write(bytes)
        val checksum = CRC32().apply { update(type); update(bytes) }
        output.writeInt(checksum.value.toInt())
    }
}

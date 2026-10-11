package dev.nuaforma.docx.internal

import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import java.io.InputStream
import java.io.OutputStream

internal object BoundedStreams {
    fun copy(input: InputStream, output: OutputStream, limit: Long, context: GenerationContext): Long {
        var total = 0L
        val buffer = ByteArray(8192)
        while (true) {
            context.checkActive()
            val count = input.read(buffer)
            if (count < 0) return total
            if (count == 0) continue
            if (count > limit - total) DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "DOCX byte budget was exceeded")
            output.write(buffer, 0, count)
            total += count
        }
    }

    class Output(private val delegate: OutputStream, private val limit: Long, private val context: GenerationContext) : OutputStream() {
        private var total = 0L
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            context.checkActive()
            if (length > limit - total) DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "DOCX output byte budget was exceeded")
            delegate.write(bytes, offset, length)
            total += length
        }
        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}

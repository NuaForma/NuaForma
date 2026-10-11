package dev.nuaforma.docx

import dev.nuaforma.contracts.GenerationException
import java.io.IOException
import java.io.OutputStream

/** Isolated document state. Closing releases the model, never caller-owned streams. */
interface DocxDocument : AutoCloseable {
    /**
     * Serialize and validate before copying to the destination. A destination I/O failure may
     * still leave partial bytes; callers needing atomic publication must supply staging storage.
     */
    @Throws(GenerationException::class, IOException::class)
    fun writeTo(output: OutputStream)

    override fun close()
}

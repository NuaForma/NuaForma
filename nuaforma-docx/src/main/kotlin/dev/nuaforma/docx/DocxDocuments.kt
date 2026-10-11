package dev.nuaforma.docx

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.docx.adapters.docx4j.Docx4jDocuments
import java.io.IOException
import java.io.InputStream

/** DOCX round-trip facade; not a template processor or a registered generation provider. */
object DocxDocuments {
    /** The same context governs reading and subsequent writing. The caller owns [input]. */
    @JvmStatic
    @JvmOverloads
    @Throws(GenerationException::class, IOException::class)
    fun open(input: InputStream, context: GenerationContext = GenerationContext()): DocxDocument =
        Docx4jDocuments.open(input, context)
}

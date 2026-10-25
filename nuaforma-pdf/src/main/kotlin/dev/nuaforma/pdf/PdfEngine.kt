package dev.nuaforma.pdf

import dev.nuaforma.pdf.adapters.pdfbox.PdfBoxWriter
import java.util.ArrayList

enum class PdfFailureCode { INVALID_RESOURCE, UNSUPPORTED_TEXT, FONT_UNAVAILABLE, FONT_EMBEDDING_FORBIDDEN, MISSING_GLYPH, CONTENT_OVERFLOW, RESOURCE_LIMIT, CANCELLED }

class PdfRenderException(val code: PdfFailureCode, message: String) : RuntimeException(message)

/** Experimental ordinary-PDF writer; no provider registration, profile or hosted-sandbox claim. */
class PdfEngine {
    fun render(document: PdfDocument, fonts: List<PdfFont>): ByteArray {
        val snapshot = ArrayList(fonts)
        require(snapshot.size in 1..16 && snapshot.map { it.id }.toSet().size == snapshot.size) { "Provide 1 to 16 uniquely named fonts" }
        require(snapshot.sumOf { it.byteSize.toLong() } <= 33_554_432) { "Font resources exceed the foundation limit" }
        val images = document.allParagraphs().flatMap { it.runs.filterIsInstance<PdfImageRun>().map { run -> run.image } } +
            document.pageSequences.flatMap { sequence ->
                sequence.blocks + sequence.flow?.bands.orEmpty().flatMap { it.blocks }
            }.filterIsInstance<PdfImage>()
        require(images.sumOf { it.byteSize.toLong() } <= 16_777_216) { "Images exceed the foundation limit" }
        return PdfBoxWriter.render(document, snapshot)
    }
}

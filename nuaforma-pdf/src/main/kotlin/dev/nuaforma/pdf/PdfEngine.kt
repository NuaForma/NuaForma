package dev.nuaforma.pdf

import dev.nuaforma.pdf.adapters.pdfbox.PdfBoxWriter
import java.util.ArrayList

enum class PdfFailureCode { INVALID_RESOURCE, UNSUPPORTED_TEXT, FONT_UNAVAILABLE, FONT_EMBEDDING_FORBIDDEN, MISSING_GLYPH, CONTENT_OVERFLOW, RESOURCE_LIMIT, CANCELLED, UNSUPPORTED_OUTPUT, VALIDATION_UNAVAILABLE, VALIDATION_FAILED }

class PdfRenderException(val code: PdfFailureCode, message: String) : RuntimeException(message)

/** Source-only PDF writer. Archival delivery requires an independent exact-byte validator. */
class PdfEngine @JvmOverloads constructor(private val validator: PdfConformanceValidator? = null) {
    @JvmOverloads fun render(document: PdfDocument, fonts: List<PdfFont>, options: PdfOutputOptions = PdfOutputOptions()): ByteArray {
        return render(document, fonts, options, PdfRenderControl())
    }

    fun render(document: PdfDocument, fonts: List<PdfFont>, options: PdfOutputOptions, control: PdfRenderControl): ByteArray {
        control.checkActive()
        document.validateNavigation(allowUnresolved = dev.nuaforma.pdf.internal.ReferenceLayouter.active(document))
        if (options.profile != PdfOutputProfile.STANDARD && options.validationEnabled && validator == null) throw PdfRenderException(PdfFailureCode.VALIDATION_UNAVAILABLE, "Archival output requires an independent validator")
        val snapshot = ArrayList(fonts)
        require(snapshot.size <= 16 && snapshot.map { it.id }.toSet().size == snapshot.size) { "Provide at most 16 uniquely named fonts; text must reference a supplied face" }
        require(snapshot.sumOf { it.byteSize.toLong() } <= 33_554_432) { "Font resources exceed the foundation limit" }
        val images = document.allParagraphs().flatMap { it.runs.filterIsInstance<PdfImageRun>().map { run -> run.image } + it.anchoredImages.map { anchor -> anchor.image } } +
            document.pageSequences.flatMap { sequence ->
                sequence.blocks + sequence.flow?.bands.orEmpty().flatMap { it.blocks }
            }.filterIsInstance<PdfImage>()
        require(images.sumOf { it.byteSize.toLong() } <= 16_777_216) { "Images exceed the foundation limit" }
        val bytes = PdfBoxWriter.render(document, snapshot, options, control)
        if (options.profile != PdfOutputProfile.STANDARD && options.validationEnabled) {
            val copy = bytes.copyOf()
            try { requireNotNull(validator).validate(copy, options.profile, control) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw PdfRenderException(PdfFailureCode.CANCELLED, "PDF validation interrupted")
            }
            catch (failure: PdfRenderException) { throw failure }
            catch (_: Exception) { throw PdfRenderException(PdfFailureCode.VALIDATION_FAILED, "Independent PDF conformance validation failed") }
            if (!copy.contentEquals(bytes)) throw PdfRenderException(PdfFailureCode.VALIDATION_FAILED, "Validation changed the PDF bytes")
            if (Thread.currentThread().isInterrupted) throw PdfRenderException(PdfFailureCode.CANCELLED, "PDF rendering interrupted")
        }
        control.checkActive()
        return bytes
    }
}

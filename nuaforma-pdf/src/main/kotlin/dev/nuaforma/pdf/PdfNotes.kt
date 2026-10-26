package dev.nuaforma.pdf

import java.util.Collections

enum class PdfNoteKind { FOOTNOTE, ENDNOTE }

/** Neutral geometry; a null continuation width spans the available text area. */
data class PdfNoteSeparator @JvmOverloads constructor(
    val height: Float = 12f, val offset: Float = 7f, val width: Float = 144f,
    val stroke: PdfBorder = PdfBorder(.6f, PdfColor(0, 0, 0)), val continuationWidth: Float? = null,
) {
    init {
        require(height.isFinite() && height in 1f..200f && offset.isFinite() && offset >= 0 && offset + stroke.width <= height) { "Invalid note separator height" }
        require(width.isFinite() && width in 1f..14_400f && (continuationWidth == null || continuationWidth.isFinite() && continuationWidth in 1f..14_400f)) { "Invalid note separator width" }
    }
}

/** Numbering is resolved in source order, as for list labels; pagination never advances a counter. */
class PdfNote @JvmOverloads constructor(
    val id: String, val kind: PdfNoteKind, val number: PdfListValue, paragraphs: List<PdfParagraph>,
    val separator: PdfNoteSeparator = PdfNoteSeparator(),
) {
    val paragraphs: List<PdfParagraph> = Collections.unmodifiableList(ArrayList(paragraphs))
    val labelTarget: String get() = semanticChildId(id, "label")
    init {
        requireSemanticId(id)
        require(number.level == 0 && number.text.length <= 32) { "Invalid note number" }
        require(this.paragraphs.size in 1..128 && this.paragraphs.all { it.anchoredImages.isEmpty() && it.contents == null && it.runs.none { run -> run is PdfImageRun || run is PdfNoteCall } }) { "Unsupported note contents" }
        val markers = this.paragraphs.sumOf { it.runs.count { run -> run is PdfNoteLabel } }
        val labels = this.paragraphs.flatMap { it.runs }.filterIsInstance<PdfTextRun>().count { it.semantics?.id == labelTarget && it.semantics.role == PdfSemanticRole.LABEL }
        val first = this.paragraphs.first().runs.first()
        require(markers == 1 && labels == 0 && first is PdfNoteLabel || markers == 0 && labels == 1 && first is PdfTextRun && first.semantics?.id == labelTarget && first.text == number.text) { "A note must begin with one label marker" }
    }
}

/** One occurrence owns a note; later citations use an ordinary reference to its labelTarget. */
data class PdfNoteCall @JvmOverloads constructor(val id: String, val noteId: String, val style: PdfTextStyle, val navigation: PdfTextNavigation? = null) : PdfInline {
    init { requireSemanticId(id); requireSemanticId(noteId) }
}

/** A definition-local label, distinct from a body call and never allowed in the main text. */
data class PdfNoteLabel @JvmOverloads constructor(val style: PdfTextStyle, val navigation: PdfTextNavigation? = null) : PdfInline

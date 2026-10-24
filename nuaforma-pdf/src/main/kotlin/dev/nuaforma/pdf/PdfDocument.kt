package dev.nuaforma.pdf

import java.util.Collections
import dev.nuaforma.pdf.internal.placements

/** Bounded document model. Parsers and source-format semantics belong to format adapters. */
class PdfDocument @JvmOverloads constructor(val page: PdfPage, blocks: List<PdfBlock>, val flow: PdfFlow? = null) {
    val blocks: List<PdfBlock> = Collections.unmodifiableList(ArrayList(blocks))

    init {
        require(this.blocks.size in 1..1_000) { "A document needs 1 to 1000 blocks" }
        require(allParagraphs().sumOf { it.runs.filterIsInstance<PdfTextRun>().sumOf { run -> run.text.length.toLong() } + (it.listLabel?.value?.text?.length ?: 0) } <= 100_000) {
            "Document text exceeds the foundation limit"
        }
    }

    internal fun allParagraphs(): List<PdfParagraph> {
        fun collect(block: PdfBlock, depth: Int): Sequence<PdfParagraph> = when (block) {
            is PdfParagraph -> sequenceOf(block)
            is PdfImage -> emptySequence()
            is PdfTextBox -> block.paragraphs.asSequence()
            is PdfTable -> {
                require(depth < 2) { "Only one nested table level is supported" }
                block.rows.asSequence().flatMap { row -> row.cells.asSequence().flatMap { cell -> cell.blocks.asSequence().flatMap { collect(it, depth + 1) } } }
            }
        }
        return (blocks + (flow?.header?.blocks ?: emptyList()) + (flow?.footer?.blocks ?: emptyList())).asSequence()
            .flatMap { collect(it, 0) }.take(2_001).toList().also { require(it.size <= 2_000) { "Paragraph budget exceeded" } }
    }
}

/** Optional bounded pagination; omitting this retains the original single-page overflow behavior. */
data class PdfFlow @JvmOverloads constructor(val header: PdfPageBand? = null, val footer: PdfPageBand? = null, val maxPages: Int = 32) {
    init { require(maxPages in 1..32) { "Invalid page budget" } }
}

/** Header offset is from page top; footer offset is from page bottom. */
class PdfPageBand(val offset: Float, val maxHeight: Float, blocks: List<PdfBlock>) {
    val blocks: List<PdfBlock> = Collections.unmodifiableList(ArrayList(blocks))
    init {
        require(offset.isFinite() && offset >= 0 && maxHeight.isFinite() && maxHeight > 0) { "Invalid page band" }
        require(this.blocks.size in 1..32) { "Invalid page-band block count" }
    }
}

data class PdfBorder(val width: Float, val color: PdfColor) {
    init { require(width.isFinite() && width in 0.1f..4f) { "Invalid border width" } }
}

class PdfTableCell @JvmOverloads constructor(blocks: List<PdfBlock>, val background: PdfColor? = null, val columnSpan: Int = 1, val rowSpan: Int = 1) {
    val blocks: List<PdfBlock> = Collections.unmodifiableList(ArrayList(blocks))
    val paragraphs: List<PdfParagraph> get() = blocks.filterIsInstance<PdfParagraph>()
    init {
        require(this.blocks.size in 1..128 && this.blocks.all { it is PdfParagraph || it is PdfTable }) { "Invalid cell blocks" }
        require(columnSpan in 1..16 && rowSpan in 1..500) { "Invalid cell span" }
    }
}

class PdfTableRow @JvmOverloads constructor(cells: List<PdfTableCell>, val allowSplit: Boolean = false, val splitOversized: Boolean = false) {
    val cells: List<PdfTableCell> = Collections.unmodifiableList(ArrayList(cells))
    init { require(this.cells.size in 0..16) { "Invalid column count" } }
}

class PdfTable @JvmOverloads constructor(
    columnWidths: List<Float>, rows: List<PdfTableRow>,
    val cellMargins: PdfMargins = PdfMargins(4f, 6f, 4f, 6f),
    val border: PdfBorder? = PdfBorder(0.5f, PdfColor(184, 197, 204)),
    val repeatedHeaderRows: Int = 0, val leftIndent: Float = 0f,
) : PdfBlock {
    val columnWidths: List<Float> = Collections.unmodifiableList(ArrayList(columnWidths))
    val rows: List<PdfTableRow> = Collections.unmodifiableList(ArrayList(rows))
    init {
        require(this.columnWidths.size in 1..16 && this.columnWidths.all { it.isFinite() && it > cellMargins.left + cellMargins.right }) { "Invalid table widths" }
        require(this.rows.size in 1..500) { "Invalid table rows" }
        require(repeatedHeaderRows in 0..minOf(4, this.rows.size - 1)) { "Invalid repeated table headers" }
        require(leftIndent.isFinite() && leftIndent in -14_400f..14_400f) { "Invalid table indentation" }
        placements()
    }


}

data class PdfMargins(val top: Float, val right: Float, val bottom: Float, val left: Float) {
    init { require(listOf(top, right, bottom, left).all { it.isFinite() && it >= 0 }) { "Margins must be finite and nonnegative" } }
}

/** Dimensions are in points, with a top-left content origin. */
data class PdfPage(val width: Float, val height: Float, val margins: PdfMargins) {
    init {
        require(width.isFinite() && height.isFinite() && width in 1f..14_400f && height in 1f..14_400f) { "Invalid page dimensions" }
        require(margins.left + margins.right < width && margins.top + margins.bottom < height) { "Margins leave no content area" }
    }
}

sealed interface PdfBlock

enum class PdfBoxAnchor { PAGE, PARAGRAPH }
enum class PdfBoxWrap { NONE, TOP_BOTTOM }
enum class PdfVerticalAlignment { TOP, CENTER, BOTTOM }

/** Positioned on the anchor's page; clockwise rotation is around the frame center. */
class PdfTextBox @JvmOverloads constructor(
    val left: Float, val top: Float, val width: Float, val height: Float,
    paragraphs: List<PdfParagraph>, val anchor: PdfBoxAnchor = PdfBoxAnchor.PARAGRAPH,
    val rotation: Float = 0f, val padding: PdfMargins = PdfMargins(3f, 6f, 3f, 6f),
    val fill: PdfColor? = null, val border: PdfBorder? = null,
    val verticalAlignment: PdfVerticalAlignment = PdfVerticalAlignment.TOP,
    val wrap: PdfBoxWrap = PdfBoxWrap.NONE,
) : PdfBlock {
    val paragraphs: List<PdfParagraph> = Collections.unmodifiableList(ArrayList(paragraphs))
    init {
        require(listOf(left, top, width, height, rotation).all { it.isFinite() }) { "Invalid frame geometry" }
        require(left in -14_400f..14_400f && top in -14_400f..14_400f && width in 1f..14_400f && height in 1f..14_400f && rotation in -360f..360f) { "Invalid frame bounds" }
        require(padding.left + padding.right < width && padding.top + padding.bottom < height) { "Frame padding leaves no content area" }
        require(this.paragraphs.size in 1..128) { "Invalid frame paragraph count" }
        require(wrap == PdfBoxWrap.NONE || rotation == 0f && top >= 0) { "Unsupported frame wrapping policy" }
    }
}

enum class PdfAlignment { LEFT, CENTER, RIGHT }

/** Physical geometry and body-flow keep rules; exact line boxes use an experimental 80% baseline.
 * Keep flags default off in the neutral model; readers resolve their source-format defaults.
 */
data class PdfParagraphFormat @JvmOverloads constructor(
    val alignment: PdfAlignment = PdfAlignment.LEFT,
    val leftIndent: Float = 0f,
    val rightIndent: Float = 0f,
    val firstLineIndent: Float = 0f,
    val exactLineHeight: Float? = null,
    val collapseSpacing: Boolean = false,
    val keepWithNext: Boolean = false,
    val keepLines: Boolean = false,
    val widowControl: Boolean = false,
    val pageBreakBefore: Boolean = false,
    val outlineLevel: Int? = null,
) {
    init {
        require(outlineLevel == null || outlineLevel in 0..8) { "Invalid outline level" }
        require(listOf(leftIndent, rightIndent).all { it.isFinite() && it in 0f..14_400f }) { "Invalid paragraph indentation" }
        require(firstLineIndent.isFinite() && firstLineIndent in -14_400f..14_400f && leftIndent + firstLineIndent >= 0) { "Invalid first-line indentation" }
        require(exactLineHeight == null || exactLineHeight.isFinite() && exactLineHeight in 1f..14_400f) { "Invalid exact line height" }
    }
}

sealed interface PdfInline
data class PdfImageRun(val image: PdfImage) : PdfInline
data class PdfPageNumber(val style: PdfTextStyle) : PdfInline

class PdfParagraph @JvmOverloads constructor(
    runs: List<PdfInline>,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 8f,
    val format: PdfParagraphFormat = PdfParagraphFormat(),
    val listLabel: PdfListLabel? = null,
) : PdfBlock {
    val runs: List<PdfInline> = Collections.unmodifiableList(ArrayList(runs))
    init {
        require(this.runs.size in 1..1_000) { "A paragraph needs 1 to 1000 runs" }
        require(spaceBefore.isFinite() && spaceAfter.isFinite() && spaceBefore in 0f..14_400f && spaceAfter in 0f..14_400f) { "Invalid paragraph spacing" }
    }
}

data class PdfColor(val red: Int, val green: Int, val blue: Int) {
    init { require(red in 0..255 && green in 0..255 && blue in 0..255) { "RGB components must be between 0 and 255" } }
}

data class PdfTextStyle @JvmOverloads constructor(
    val fontId: String,
    val size: Float,
    val color: PdfColor = PdfColor(0, 0, 0),
) {
    init {
        require(fontId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid font ID" }
        require(size.isFinite() && size in 1f..200f) { "Invalid font size" }
    }
}

data class PdfTextRun(val text: String, val style: PdfTextStyle) : PdfInline {
    init { require(text.isNotEmpty() && text.length <= 100_000) { "A text run must contain bounded nonempty text" } }
}

/** Caller-supplied standalone TrueType font. No filesystem or system-font lookup. */
class PdfFont(val id: String, bytes: ByteArray) {
    private val content: ByteArray
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid font ID" }
        require(bytes.size in 1..8_388_608) { "Font exceeds the foundation limit" }
        content = bytes.copyOf()
    }
    fun bytes(): ByteArray = content.copyOf()
    internal val byteSize: Int get() = content.size
}

/** PNG bytes and explicit physical dimensions; no external resource lookup. */
class PdfImage(bytes: ByteArray, val width: Float, val height: Float) : PdfBlock {
    private val content: ByteArray
    init {
        require(bytes.size in 24..4_194_304) { "PNG exceeds the foundation limit" }
        require(width.isFinite() && height.isFinite() && width in 1f..14_400f && height in 1f..14_400f) { "Invalid image dimensions" }
        content = bytes.copyOf()
    }
    fun bytes(): ByteArray = content.copyOf()
    internal val byteSize: Int get() = content.size
}

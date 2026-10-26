package dev.nuaforma.pdf

import java.util.Collections
import dev.nuaforma.pdf.internal.placements

/** Bounded document model. Parsers and source-format semantics belong to format adapters. */
class PdfDocument @JvmOverloads constructor(pageSequences: List<PdfPageSequence>, val maxPages: Int = 32, referenceTargets: List<PdfReferenceTarget> = emptyList()) {
    val referenceTargets: List<PdfReferenceTarget> = Collections.unmodifiableList(ArrayList(referenceTargets))
    val pageSequences: List<PdfPageSequence> = Collections.unmodifiableList(ArrayList(pageSequences)).also {
        require(it.size in 1..32) { "A document needs 1 to 32 page sequences" }
    }

    /** Existing single-policy construction retains its original page budget and layout path. */
    @JvmOverloads constructor(page: PdfPage, blocks: List<PdfBlock>, flow: PdfFlow? = null, referenceTargets: List<PdfReferenceTarget> = emptyList()) :
        this(listOf(PdfPageSequence(page, blocks, flow)), flow?.maxPages ?: 1, referenceTargets)

    /** First-sequence views retained for existing callers; use pageSequences for the whole document. */
    val page: PdfPage get() = pageSequences.first().page
    val blocks: List<PdfBlock> get() = pageSequences.first().blocks
    val flow: PdfFlow? get() = pageSequences.first().flow

    init {
        require(maxPages in 1..32 && this.pageSequences.size <= maxPages) { "Invalid document page budget" }
        require(this.pageSequences.sumOf { it.blocks.size } <= 1_000) { "Document block budget exceeded" }
        require(allParagraphs().sumOf { it.runs.sumOf { run -> when (run) { is PdfTextRun -> run.text.length.toLong(); is PdfPageNumber -> 4L; is PdfPageField -> 2L; else -> 0L } } + (it.listLabel?.value?.text?.length ?: 0) } <= 100_000) {
            "Document text exceeds the foundation limit"
        }
        require(allParagraphs().sumOf { it.runs.count { run -> run is PdfTab } } <= 2_000) { "Tab control budget exceeded" }
        require(this.referenceTargets.size <= 1_000 && this.referenceTargets.map { it.name }.distinct().size == this.referenceTargets.size) { "Duplicate or excessive reference targets" }
        require(this.referenceTargets.sumOf { it.runs.size } <= 10_000 && this.referenceTargets.sumOf { it.runs.filterIsInstance<PdfTextRun>().sumOf { run -> run.text.length.toLong() } } <= 100_000) { "Reference-range budget exceeded" }
        val fields = allParagraphs().flatMap { it.runs }.mapNotNull { when (it) { is PdfReferenceField -> it.id; is PdfSequenceField -> it.id; else -> null } }
        require(fields.size <= 1_000 && fields.distinct().size == fields.size) { "Duplicate or excessive field identities" }
    }

    internal fun validateNavigation(allowUnresolved: Boolean = false) {
        val navigation = allParagraphs().flatMap { it.runs }.mapNotNull { when (it) {
            is PdfTextRun -> it.navigation; is PdfReferenceField -> it.navigation; is PdfSequenceField -> it.navigation; else -> null
        } }
        require(navigation.size <= 2_000) { "Navigation budget exceeded" }
        val destinations = navigation.flatMap { it.destinations }.map { it.name }
        require(destinations.size <= 1_000 && destinations.distinct().size == destinations.size) { "Duplicate or excessive destinations" }
        val names = destinations.toSet()
        require(allowUnresolved || navigation.mapNotNull { it.link as? PdfLink.Internal }.all { it.destination in names }) { "Missing internal destination" }
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
        return pageSequences.asSequence().flatMap { sequence ->
            (sequence.blocks + sequence.flow?.bands.orEmpty().flatMap { it.blocks }).asSequence()
        }
            .flatMap { collect(it, 0) }.take(2_001).toList().also { require(it.size <= 2_000) { "Paragraph budget exceeded" } }
    }
}

/** A resolved physical page policy over a content range. Every sequence starts on a fresh page.
 * Adapters resolve source section/master inheritance before constructing these immutable ranges.
 */
class PdfPageSequence @JvmOverloads constructor(
    val page: PdfPage, blocks: List<PdfBlock>, val flow: PdfFlow? = null,
    val numbering: PdfPageNumbering = PdfPageNumbering(),
) {
    val blocks: List<PdfBlock> = Collections.unmodifiableList(ArrayList(blocks))
    init { require(this.blocks.size in 1..1_000) { "A page sequence needs 1 to 1000 blocks" } }
}

/** Null start continues preceding display numbers. Optional padding matches the preceding numbering parity.
 * Padding uses the new sequence's page geometry and has no bands or sequence-page contribution.
 */
data class PdfPageNumbering @JvmOverloads constructor(val start: Int? = null, val alignParity: Boolean = false) {
    init {
        require(start == null || start in 0..9_999) { "Invalid page-number start" }
        require(!alignParity || start != null) { "Parity alignment requires an explicit start" }
    }
}

/** Optional bounded pagination; omitting this retains the original single-page overflow behavior. */
data class PdfFlow @JvmOverloads constructor(
    val header: PdfPageBand? = null, val footer: PdfPageBand? = null, val maxPages: Int = 32,
    val firstPageBands: PdfPageBands? = null, val evenPageBands: PdfPageBands? = null,
) {
    init { require(maxPages in 1..32) { "Invalid page budget" } }
    internal val bands: List<PdfPageBand> get() = listOfNotNull(header, footer, firstPageBands?.header, firstPageBands?.footer, evenPageBands?.header, evenPageBands?.footer)
}

/** Null policy means shared bands; an explicit empty pair means no header/footer on that variant. */
data class PdfPageBands @JvmOverloads constructor(val header: PdfPageBand? = null, val footer: PdfPageBand? = null)

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

/** Adapters supply a neutral precedence; larger priorities win at a shared table edge. */
data class PdfEdgeStroke @JvmOverloads constructor(val border: PdfBorder, val priority: Long = 0) {
    init { require(priority >= 0) { "Invalid edge priority" } }
}

/** Resolved cell edges. Null edges have no stroke; a null cell policy inherits the table border. */
data class PdfCellBorders @JvmOverloads constructor(
    val top: PdfEdgeStroke? = null, val right: PdfEdgeStroke? = null,
    val bottom: PdfEdgeStroke? = null, val left: PdfEdgeStroke? = null,
)

class PdfTableCell @JvmOverloads constructor(
    blocks: List<PdfBlock>, val background: PdfColor? = null, val columnSpan: Int = 1, val rowSpan: Int = 1,
    val verticalAlignment: PdfVerticalAlignment = PdfVerticalAlignment.TOP,
    val borders: PdfCellBorders? = null,
) {
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

/** FIT_CONTENTS replaces preferred grid widths with natural, unwrapped text widths. */
enum class PdfTableWidthPolicy { FIXED, FIT_CONTENTS }

class PdfTable @JvmOverloads constructor(
    columnWidths: List<Float>, rows: List<PdfTableRow>,
    val cellMargins: PdfMargins = PdfMargins(4f, 6f, 4f, 6f),
    val border: PdfBorder? = PdfBorder(0.5f, PdfColor(184, 197, 204)),
    val repeatedHeaderRows: Int = 0, val leftIndent: Float = 0f,
    val widthPolicy: PdfTableWidthPolicy = PdfTableWidthPolicy.FIXED,
    override val semantics: PdfBlockSemantics? = null,
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

sealed interface PdfBlock { val semantics: PdfBlockSemantics? get() = null }

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
    override val semantics: PdfBlockSemantics? = null,
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
    val tabs: PdfTabSettings? = null,
    val automaticLineSpacing: PdfAutomaticLineSpacing? = null,
    /** Explicit bidi base direction; null preserves the original simple-text profile. */
    val textDirection: PdfTextDirection? = null,
) {
    init {
        require(exactLineHeight == null || automaticLineSpacing == null) { "Conflicting line policies" }
        require(outlineLevel == null || outlineLevel in 0..8) { "Invalid outline level" }
        require(listOf(leftIndent, rightIndent).all { it.isFinite() && it in 0f..14_400f }) { "Invalid paragraph indentation" }
        require(firstLineIndent.isFinite() && firstLineIndent in -14_400f..14_400f && leftIndent + firstLineIndent >= 0) { "Invalid first-line indentation" }
        require(exactLineHeight == null || exactLineHeight.isFinite() && exactLineHeight in 1f..14_400f) { "Invalid exact line height" }
    }
}

sealed interface PdfInline
data class PdfImageRun(val image: PdfImage) : PdfInline
/** Decimal fields resolved after body pagination, within fixed reserved page bands. */
sealed interface PdfPageField : PdfInline { val style: PdfTextStyle }
data class PdfPageNumber(override val style: PdfTextStyle) : PdfPageField
data class PdfPageCount(override val style: PdfTextStyle) : PdfPageField
data class PdfSequencePageCount(override val style: PdfTextStyle) : PdfPageField

class PdfParagraph @JvmOverloads constructor(
    runs: List<PdfInline>,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 8f,
    val format: PdfParagraphFormat = PdfParagraphFormat(),
    val listLabel: PdfListLabel? = null,
    anchoredImages: List<PdfAnchoredImage> = emptyList(),
    override val semantics: PdfBlockSemantics? = null,
    val contents: PdfContents? = null,
) : PdfBlock {
    val runs: List<PdfInline> = Collections.unmodifiableList(ArrayList(runs))
    val anchoredImages: List<PdfAnchoredImage> = Collections.unmodifiableList(ArrayList(anchoredImages))
    init {
        require(this.anchoredImages.size <= 8) { "Anchored image budget exceeded" }
        require(this.runs.size in 1..1_000) { "A paragraph needs 1 to 1000 runs" }
        require(this.runs.count { it is PdfTab } <= 32) { "Paragraph tab budget exceeded" }
        require(spaceBefore.isFinite() && spaceAfter.isFinite() && spaceBefore in 0f..14_400f && spaceAfter in 0f..14_400f) { "Invalid paragraph spacing" }
        require(contents == null || this.runs.all { it is PdfTextRun && it.text.isBlank() } && listLabel == null && this.anchoredImages.isEmpty()) { "Contents markers cannot hide ordinary content" }
        require(contents == null || semantics == null || semantics.role == PdfSemanticRole.CONTENTS) { "Contents markers require contents semantics" }
        require(semantics == null || semantics.role in setOf(PdfSemanticRole.PARAGRAPH, PdfSemanticRole.HEADING, PdfSemanticRole.CAPTION, PdfSemanticRole.CONTENTS, PdfSemanticRole.CONTENTS_ENTRY)) { "Invalid paragraph semantics" }
    }
}

data class PdfColor(val red: Int, val green: Int, val blue: Int) {
    init { require(red in 0..255 && green in 0..255 && blue in 0..255) { "RGB components must be between 0 and 255" } }
}

data class PdfTextStyle @JvmOverloads constructor(
    val fontId: String,
    val size: Float,
    val color: PdfColor = PdfColor(0, 0, 0),
    val languageTag: String? = null,
    val underline: Boolean = false,
) {
    init {
        require(languageTag == null || languageTag.length <= 64 && languageTag.matches(Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*"))) { "Invalid text language tag" }
        require(fontId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid font ID" }
        require(size.isFinite() && size in 1f..200f) { "Invalid font size" }
    }
}

data class PdfTextRun @JvmOverloads constructor(val text: String, val style: PdfTextStyle, val navigation: PdfTextNavigation? = null, val semantics: PdfSpanSemantics? = null) : PdfInline {
    init {
        require(text.isNotEmpty() && text.length <= 100_000) { "A text run must contain bounded nonempty text" }
        require(navigation?.destinations.isNullOrEmpty() || text.isNotBlank()) { "Destination requires visible text" }
    }
}

/** Caller-supplied standalone TrueType font. No filesystem or system-font lookup. */
enum class PdfFontEmbedding { SUBSET, FULL }

class PdfFont @JvmOverloads constructor(val id: String, bytes: ByteArray, val embedding: PdfFontEmbedding = PdfFontEmbedding.SUBSET) {
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
class PdfImage @JvmOverloads constructor(bytes: ByteArray, val width: Float, val height: Float, val crop: PdfImageCrop = PdfImageCrop(), override val semantics: PdfBlockSemantics? = null) : PdfBlock {
    private val content: ByteArray
    init {
        require(bytes.size in 24..4_194_304) { "PNG exceeds the foundation limit" }
        require(width.isFinite() && height.isFinite() && width in 1f..14_400f && height in 1f..14_400f) { "Invalid image dimensions" }
        content = bytes.copyOf()
    }
    fun bytes(): ByteArray = content.copyOf()
    internal val byteSize: Int get() = content.size
}

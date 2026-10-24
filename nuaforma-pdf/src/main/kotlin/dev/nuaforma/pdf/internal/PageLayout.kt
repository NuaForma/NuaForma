package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

internal data class TextMetrics(val width: Float, val ascent: Float, val descent: Float)
internal fun interface TextMeasurer { fun measure(text: String, style: PdfTextStyle): TextMetrics }
internal sealed interface PositionedElement
internal data class PositionedText(val text: String, val style: PdfTextStyle, val x: Float, val baseline: Float) : PositionedElement
internal data class PositionedImage(val image: PdfImage, val x: Float, val top: Float) : PositionedElement
internal data class PositionedRectangle(val x: Float, val top: Float, val width: Float, val height: Float, val fill: PdfColor?, val border: PdfBorder?) : PositionedElement
internal data class PositionedBox(val box: PdfTextBox, val x: Float, val top: Float, val elements: List<PositionedElement>) : PositionedElement
internal data class PageLayout(val page: PdfPage, val elements: List<PositionedElement>)

internal object PageLayouter {
    fun layout(document: PdfDocument, measurer: TextMeasurer): PageLayout {
        val page = document.page
        val width = page.width - page.margins.left - page.margins.right
        val bottom = page.height - page.margins.bottom
        var top = page.margins.top
        var previousAfter = 0f
        val elements = mutableListOf<PositionedElement>()
        for (block in document.blocks) {
            checkCancellation()
            when (block) {
                is PdfTextBox -> {
                    if (block.wrap != PdfBoxWrap.NONE) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Wrapped frames require the paginated profile")
                    val placed = BoxLayouter.layout(block, page, top, measurer)
                    elements += placed
                }
                is PdfTable -> throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Tables require the paginated profile")
                is PdfImage -> {
                    if (block.width > width || top + block.height > bottom) overflow()
                    elements += PositionedImage(block, page.margins.left, top)
                    top += block.height
                    previousAfter = 0f
                }
                is PdfParagraph -> {
                    val format = block.format
                    top += if (format.collapseSpacing) maxOf(0f, block.spaceBefore - previousAfter) else block.spaceBefore
                    var firstLine = true
                    fun availableWidth() = width - format.leftIndent - format.rightIndent - if (firstLine) format.firstLineIndent else 0f
                    if (availableWidth() <= 0 || width - format.leftIndent - format.rightIndent <= 0) overflow()
                    var line = mutableListOf<Fragment>()
                    var lineWidth = 0f
                    val spaces = mutableListOf<Fragment>()
                    fun flush() {
                        if (line.isEmpty()) return
                        val ascent = line.maxOf { it.metrics.ascent }
                        val descent = line.maxOf { it.metrics.descent }
                        val height = format.exactLineHeight ?: (ascent + descent) * 1.2f
                        if (top + height > bottom) overflow()
                        val baseline = top + (format.exactLineHeight?.times(0.8f) ?: ascent)
                        if (baseline - ascent < page.margins.top - 0.01f || baseline + descent > bottom + 0.01f) overflow()
                        val remaining = availableWidth() - lineWidth
                        var x = page.margins.left + format.leftIndent + (if (firstLine) format.firstLineIndent else 0f) + when (format.alignment) {
                            PdfAlignment.LEFT -> 0f
                            PdfAlignment.CENTER -> remaining / 2
                            PdfAlignment.RIGHT -> remaining
                        }
                        for (fragment in line) {
                            if (fragment.image != null) elements += PositionedImage(fragment.image, x, baseline - fragment.image.height)
                            else elements += PositionedText(fragment.text, requireNotNull(fragment.style), x, baseline)
                            x += fragment.metrics.width
                        }
                        top += height
                        line = mutableListOf()
                        lineWidth = 0f
                        firstLine = false
                    }
                    for (word in words(block.runs, measurer)) {
                        checkCancellation()
                        if (word.all { it.text.all { character -> character == ' ' } }) {
                            spaces += word
                            continue
                        }
                        val wordWidth = word.sumOf { it.metrics.width.toDouble() }.toFloat()
                        val spaceWidth = spaces.sumOf { it.metrics.width.toDouble() }.toFloat()
                        if (lineWidth + spaceWidth + wordWidth > availableWidth()) {
                            if (line.isEmpty()) overflow()
                            flush()
                            spaces.clear()
                        }
                        if (wordWidth > availableWidth()) overflow()
                        line += spaces
                        lineWidth += spaces.sumOf { it.metrics.width.toDouble() }.toFloat()
                        spaces.clear()
                        line += word
                        lineWidth += wordWidth
                    }
                    if (line.isEmpty() && spaces.isNotEmpty()) {
                        if (spaces.sumOf { it.metrics.width.toDouble() } > availableWidth()) overflow()
                        line += spaces
                        lineWidth = spaces.sumOf { it.metrics.width.toDouble() }.toFloat()
                    }
                    flush()
                    top += block.spaceAfter
                    previousAfter = block.spaceAfter
                }
            }
        }
        return PageLayout(page, elements)
    }

    private data class Fragment(val text: String, val style: PdfTextStyle?, val metrics: TextMetrics, val image: PdfImage? = null)

    private fun words(runs: List<PdfInline>, measurer: TextMeasurer): List<List<Fragment>> {
        val groups = mutableListOf<MutableList<Fragment>>()
        var previousSpace: Boolean? = null
        for (run in runs) {
            if (run is PdfPageNumber) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Page fields require page-band resolution")
            if (run is PdfImageRun) {
                groups += mutableListOf(Fragment("\uFFFC", null, TextMetrics(run.image.width, run.image.height, 0f), run.image))
                previousSpace = null
                continue
            }
            run as PdfTextRun
            requireSupportedText(run.text)
            for (match in Regex(" +|[^ ]+").findAll(run.text)) {
                val space = match.value[0] == ' '
                if (space != previousSpace) groups += mutableListOf<Fragment>()
                groups.last() += Fragment(match.value, run.style, measurer.measure(match.value, run.style))
                previousSpace = space
            }
        }
        return groups
    }

    private fun requireSupportedText(text: String) {
        text.codePoints().forEach { point ->
            val script = Character.UnicodeScript.of(point)
            val category = Character.getType(point)
            if (script !in setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON) ||
                Character.isISOControl(point) || category in setOf(Character.FORMAT.toInt(), Character.SURROGATE.toInt(), Character.UNASSIGNED.toInt()) ||
                point != 32 && Character.isWhitespace(point)) {
                throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Text requires unsupported shaping or control handling")
            }
        }
    }

    private fun overflow(): Nothing = throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Content does not fit the single-page foundation profile")
    internal fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw PdfRenderException(PdfFailureCode.CANCELLED, "PDF rendering cancelled")
    }
}

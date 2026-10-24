package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

internal data class TextMetrics(val width: Float, val ascent: Float, val descent: Float, val naturalAscent: Float = ascent, val naturalDescent: Float = descent, val lineGap: Float = 0f)
internal fun interface TextMeasurer { fun measure(text: String, style: PdfTextStyle): TextMetrics }
internal sealed interface PositionedElement
internal data class PositionedText(val text: String, val style: PdfTextStyle, val x: Float, val baseline: Float, val decoration: Boolean = false) : PositionedElement
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
                    if (block.format.pageBreakBefore && elements.isNotEmpty()) overflow()
                    val format = block.format
                    val automatic = automaticBox(block, measurer)
                    val label = block.listLabel
                    if (label != null && format.alignment != PdfAlignment.LEFT) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "List body alignment is outside the qualified profile")
                    label?.let { requireSupportedText(it.value.text) }
                    val marker = label?.let { measurer.measure(it.value.text, it.style) }
                    val markerAnchor = format.leftIndent + format.firstLineIndent
                    val markerLeft = markerAnchor - (marker?.width ?: 0f) * when (label?.alignment) { PdfAlignment.RIGHT -> 1f; PdfAlignment.CENTER -> .5f; else -> 0f }
                    val markerEnd = markerLeft + (marker?.width ?: 0f)
                    if (label != null && (markerLeft < -.001f || markerEnd > width - format.rightIndent + .001f)) overflow()
                    val firstTextLeft = if (label == null) format.leftIndent + format.firstLineIndent else when (label.suffix) {
                        PdfListSuffix.NOTHING -> markerEnd
                        PdfListSuffix.SPACE -> markerEnd + measurer.measure(" ", label.style).width
                        PdfListSuffix.TAB -> label.tabStop?.takeIf { it > markerEnd + .001f }
                            ?: ((kotlin.math.floor(markerEnd / label.defaultTabInterval) + 1) * label.defaultTabInterval)
                    }
                    top += if (format.collapseSpacing) maxOf(0f, block.spaceBefore - previousAfter) else block.spaceBefore
                    var firstLine = true
                    fun lineLeft() = if (firstLine) firstTextLeft else format.leftIndent
                    fun availableWidth() = width - format.rightIndent - lineLeft()
                    if (availableWidth() <= 0 || width - format.leftIndent - format.rightIndent <= 0) overflow()
                    var line = mutableListOf<Fragment>()
                    var lineWidth = 0f
                    val spaces = mutableListOf<Fragment>()
                    fun flush() {
                        if (line.isEmpty()) return
                        val ascent = maxOf(line.maxOf { if (automatic == null) it.metrics.ascent else it.metrics.naturalAscent }, if (firstLine) (if (automatic == null) marker?.ascent else marker?.naturalAscent) ?: 0f else 0f)
                        val descent = maxOf(line.maxOf { if (automatic == null) it.metrics.descent else it.metrics.naturalDescent }, if (firstLine) (if (automatic == null) marker?.descent else marker?.naturalDescent) ?: 0f else 0f)
                        val height = automatic?.first ?: format.exactLineHeight ?: (ascent + descent) * 1.2f
                        if (top + height > bottom) overflow()
                        val baseline = top + (automatic?.second ?: format.exactLineHeight?.times(0.8f) ?: ascent)
                        if (baseline - ascent < page.margins.top - 0.01f || baseline + descent > bottom + 0.01f) overflow()
                        val remaining = availableWidth() - lineWidth
                        if (firstLine && label != null) elements += PositionedText(label.value.text, label.style, page.margins.left + markerLeft, baseline)
                        var x = page.margins.left + lineLeft() + when (format.alignment) {
                            PdfAlignment.LEFT -> 0f
                            PdfAlignment.CENTER -> remaining / 2
                            PdfAlignment.RIGHT -> remaining
                        }
                        for (fragment in line) {
                            if (fragment.image != null) elements += PositionedImage(fragment.image, x, baseline - fragment.image.height)
                            else elements += PositionedText(fragment.text, requireNotNull(fragment.style), x + fragment.paintOffset, baseline, fragment.decoration)
                            x += fragment.metrics.width
                        }
                        top += height
                        line = mutableListOf()
                        lineWidth = 0f
                        firstLine = false
                    }
                    val groups = words(block.runs, measurer)
                    for ((groupIndex, word) in groups.withIndex()) {
                        checkCancellation()
                        val control = word.singleOrNull()?.tab
                        if (control != null) {
                            if (format.alignment != PdfAlignment.LEFT) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Tab paragraphs require left body alignment")
                            line += spaces; lineWidth += spaces.sumOf { it.metrics.width.toDouble() }.toFloat(); spaces.clear()
                            val settings = format.tabs ?: PdfTabSettings()
                            val cursor = lineLeft() + lineWidth
                            val following = groups.drop(groupIndex + 1).takeWhile { group -> group.none { it.tab != null } }.flatten()
                            val payloadWidth = following.sumOf { it.metrics.width.toDouble() }.toFloat()
                            var decimalWidth = 0f
                            for (fragment in following) {
                                if (settings.decimalSeparator in fragment.text) {
                                    decimalWidth += measurer.measure(fragment.text.substringBefore(settings.decimalSeparator), requireNotNull(fragment.style)).width
                                    break
                                }
                                decimalWidth += fragment.metrics.width
                            }
                            val explicit = settings.stops.firstOrNull { it.position > cursor + .001f }
                            val stop = explicit ?: PdfTabStop((kotlin.math.floor((cursor + .001f) / settings.defaultInterval) + 1) * settings.defaultInterval)
                            val start = stop.position - when (stop.alignment) {
                                PdfTabAlignment.LEFT -> 0f; PdfTabAlignment.RIGHT -> payloadWidth
                                PdfTabAlignment.CENTER -> payloadWidth / 2; PdfTabAlignment.DECIMAL -> decimalWidth
                            }
                            if (start < cursor - .001f || start > width - format.rightIndent + .001f || stop.alignment != PdfTabAlignment.LEFT && start + payloadWidth > width - format.rightIndent + .001f) overflow()
                            val gap = maxOf(0f, start - cursor)
                            val symbol = when (stop.leader) { PdfTabLeader.NONE -> " "; PdfTabLeader.DOT -> "."; PdfTabLeader.HYPHEN -> "-"; PdfTabLeader.UNDERSCORE -> "_" }
                            val metrics = measurer.measure(symbol, control.style)
                            val count = if (stop.leader == PdfTabLeader.NONE) 1 else kotlin.math.floor(gap / metrics.width).toInt()
                            if (count > 4096) throw PdfRenderException(PdfFailureCode.RESOURCE_LIMIT, "Tab leader budget exceeded")
                            val text = if (count > 0) symbol.repeat(count) else " "
                            val offset = if (stop.leader == PdfTabLeader.NONE || count == 0) 0f else (gap - count * metrics.width) / 2
                            line += Fragment(text, control.style, metrics.copy(width = gap), paintOffset = offset, decoration = stop.leader != PdfTabLeader.NONE)
                            lineWidth += gap
                            continue
                        }
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

    internal fun automaticBox(p: PdfParagraph, measurer: TextMeasurer): Pair<Float, Float>? {
        val policy = p.format.automaticLineSpacing ?: return null
        val styles = p.runs.map { when (it) {
            is PdfTextRun -> it.style; is PdfTab -> it.style; is PdfPageNumber -> it.style
            else -> throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Automatic inline image spacing is not qualified")
        } } + listOfNotNull(p.listLabel?.style)
        val metrics = styles.map { measurer.measure(" ", it) }
        if (metrics.any { !it.naturalAscent.isFinite() || !it.naturalDescent.isFinite() || !it.lineGap.isFinite() })
            throw PdfRenderException(PdfFailureCode.INVALID_RESOURCE, "Natural font line metrics are unavailable")
        val boxes = metrics.map { Triple(it.naturalAscent, it.naturalDescent, it.lineGap) }.distinct()
        if (boxes.size != 1) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Mixed natural line boxes are not qualified")
        val (ascent, descent, gap) = boxes.single()
        val height = (ascent + descent + gap) * policy.multiple
        if (!height.isFinite() || height !in 1f..14_400f || height < ascent + descent + gap) overflow()
        return height to (ascent + gap)
    }

    private data class Fragment(val text: String, val style: PdfTextStyle?, val metrics: TextMetrics, val image: PdfImage? = null, val tab: PdfTab? = null, val paintOffset: Float = 0f, val decoration: Boolean = false)

    private fun words(runs: List<PdfInline>, measurer: TextMeasurer): List<List<Fragment>> {
        val groups = mutableListOf<MutableList<Fragment>>()
        var previousSpace: Boolean? = null
        for (run in runs) {
            if (run is PdfPageNumber) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Page fields require page-band resolution")
            if (run is PdfTab) {
                groups += mutableListOf(Fragment("\t", run.style, TextMetrics(0f, 0f, 0f), tab = run))
                previousSpace = null
                continue
            }
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

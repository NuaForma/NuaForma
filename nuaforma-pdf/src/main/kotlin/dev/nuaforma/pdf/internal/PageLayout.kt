package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

internal data class TextMetrics(val width: Float, val ascent: Float, val descent: Float, val naturalAscent: Float = ascent, val naturalDescent: Float = descent, val lineGap: Float = 0f)
internal fun interface TextMeasurer {

    fun measure(text: String, style: PdfTextStyle): TextMetrics

    fun ink(text: String, style: PdfTextStyle): TextMetrics = measure(text, style)

    fun gap(before: String, spaces: String, after: String, style: PdfTextStyle): TextMetrics = measure(spaces, style)

}
internal sealed interface PositionedElement
internal data class PositionedDestination(val targets: List<PdfDestination>, val x: Float, val top: Float,
    val pageOffset: PdfParagraphPageOffset? = null) : PositionedElement
internal data class PositionedVector(val run: PdfVectorRun, val x: Float, val top: Float,
    val pageOffset: PdfParagraphPageOffset? = null) : PositionedElement
internal enum class StrokeLayer { FLOW, BACKGROUND, FOREGROUND }
internal data class PositionedStroke(val x: Float, val top: Float, val endX: Float, val endY: Float, val stroke: PdfLineStroke,
    val pageX: Boolean = false, val pageY: Boolean = false, val layer: StrokeLayer = StrokeLayer.FLOW,
    val joinOwner: PdfParagraph? = null, val joinFollowing: PdfParagraph? = null, val pageOffset: PdfParagraphPageOffset? = null) : PositionedElement
internal data class TabLinkArea(val offset: Float, val width: Float)
internal data class TextBackground(val width: Float, val ascent: Float, val height: Float, val offset: Float = 0f)
internal data class PositionedText(val text: String, val style: PdfTextStyle, val x: Float, val baseline: Float, val decoration: Boolean = false, val navigation: PdfTextNavigation? = null, val logicalOwner: String? = null, val span: PdfSpanSemantics? = null, val logicalOffset: Int? = null, val pageOffset: PdfParagraphPageOffset? = null, val trailingDecorationWidth: Float? = null, val requiresPhysicalBounds: Boolean = false, val background: TextBackground? = null,
    val physicalLeftOverhang: Float? = null, val tabLinkArea: TabLinkArea? = null) : PositionedElement
internal data class PositionedImage(val image: PdfImage, val x: Float, val top: Float, val foreground: Boolean = false, val pageOffset: PdfParagraphPageOffset? = null, val pageY: Boolean = false, val requiresPhysicalBounds: Boolean = false, val clip: RectangleClip? = null, val drawingOrder: PdfDrawingOrder? = null, val link: PdfLink.External? = null, val headerUnderlay: Boolean = false) : PositionedElement
internal data class RectangleClip(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    fun translated(x: Float, y: Float) = RectangleClip(left + x, top + y, right + x, bottom + y)

    fun intersect(other: RectangleClip) = RectangleClip(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))

}

internal data class PositionedRectangle(val x: Float, val top: Float, val width: Float, val height: Float, val fill: PdfColor?, val border: PdfBorder?,
    val paragraph: PdfParagraph? = null, val first: Boolean = false, val following: PdfParagraph? = null,
    val pageOffset: PdfParagraphPageOffset? = null, val clip: RectangleClip? = null) : PositionedElement
internal data class PositionedTableBorder(val x: Float, val top: Float, val length: Float, val horizontal: Boolean, val stroke: PdfEdgeStroke, val group: Any) : PositionedElement
internal data class PositionedBox(val box: PdfTextBox, val x: Float, val top: Float, val elements: List<PositionedElement>, val pageY: Boolean = false,
    val contentHeight: Float = box.height) : PositionedElement
internal data class MeasuredLineBox(val from: Int, val until: Int, val top: Float, val height: Float, val pageBreakAfter: Boolean = false)
internal data class PageLayout(
    val page: PdfPage,
    val elements: List<PositionedElement>,
    val lineBoxes: List<MeasuredLineBox> = emptyList(),
    val pageFrame: PdfPageFrame? = null,
)

internal object PageLayouter {
    private const val softHyphen = '\u00AD'
    private val wordFragments = Regex(" +|$softHyphen|[^ $softHyphen]+")
    private val simpleScripts = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK, Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.COMMON)
    private val unshapedForbiddenTypes = setOf(Character.FORMAT.toInt(), Character.SURROGATE.toInt(), Character.UNASSIGNED.toInt(), Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())

    fun layout(document: PdfDocument, measurer: TextMeasurer, lineArea: ((Int) -> Pair<Float, Float>)? = null, measurement: Boolean = false,
        permitMarginMarker: Boolean = false, rightMarginAllowance: Float = 0f): PageLayout {
        val page = document.page
        val width = page.width - page.margins.left - page.margins.right
        val bottom = page.height - page.margins.bottom
        var top = page.margins.top
        var previousAfter = 0f
        val elements = mutableListOf<PositionedElement>()
        val lineBoxes = mutableListOf<MeasuredLineBox>()
        for ((blockIndex, block) in document.blocks.withIndex()) {
            checkCancellation()
            when (block) {
                is PdfTextBox -> {
                    if (block.wrap != PdfBoxWrap.NONE) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Wrapped frames require the paginated profile")
                    val placed = BoxLayouter.layout(block, page, top, measurer)
                    elements += placed
                }
                is PdfTable -> throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "Tables require the paginated profile")
                is PdfImage -> {
                    if (block.width > width) overflow("Image exceeds area width", block.width, width)
                    if (top + block.height > bottom) overflow("Image exceeds remaining area height", block.height, bottom - top)
                    elements += PositionedImage(block, page.margins.left, top)
                    top += block.height
                    previousAfter = 0f
                }
                is PdfParagraph -> {
                    if (!measurement && block.runs.any { it is PdfPageBreak })
                        throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Inline page breaks require body pagination")
                    if (!measurement) ParagraphBounds.check(block, page)
                    val paragraphStart = elements.size
                    if (block.anchoredImages.isNotEmpty()) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Anchored images require body pagination")
                    if (block.format.pageBreakBefore && elements.isNotEmpty()) overflow("Page break requires another layout area", 2f, 1f, "areas")
                    val format = block.format
                    if (format.textDirection != null && block.runs.filterIsInstance<PdfTextRun>().any { it.navigation != null || it.semantics != null })
                        throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Bidi navigation is not qualified")
                    if (format.alignment == PdfAlignment.JUSTIFY && format.textDirection != null)
                        throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Bidi justification is not qualified")
                    if (format.textDirection == PdfTextDirection.RIGHT_TO_LEFT && (block.runs.any { it is PdfTextRun && it.style.characterSpacing != 0f } ||
                            block.listLabel?.style?.characterSpacing?.let { it != 0f } == true))
                        throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "RTL character spacing is not qualified")
                    val automaticSpacing = format.automaticLineSpacing != null || block.minimumLineHeight != null
                    if (automaticSpacing && format.textDirection != null) automaticBox(block, measurer, allowMixed = false)
                    if (format.textDirection != null && block.runs.any { it !is PdfTextRun && it !is PdfLineBreak })
                        throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Bidi tabs and inline images are not qualified")
                    if (format.textDirection == PdfTextDirection.RIGHT_TO_LEFT && (block.listLabel != null || format.firstLineIndent != 0f))
                        throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "RTL hanging or first-line indentation is not qualified")
                    if (format.textDirection != null) {
                        val points = block.runs.filterIsInstance<PdfTextRun>().joinToString("") { it.text }.codePoints().toArray()
                        if (points.any { Character.UnicodeScript.of(it) == Character.UnicodeScript.HEBREW } &&
                            (format.textDirection != PdfTextDirection.RIGHT_TO_LEFT || points.any { Character.getDirectionality(it) == Character.DIRECTIONALITY_LEFT_TO_RIGHT }))
                            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Mixed Hebrew and LTR text is not qualified for logical extraction")
                    }
                    val label = block.listLabel
                    if (label != null && format.alignment !in setOf(PdfAlignment.LEFT, PdfAlignment.JUSTIFY)) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "List body alignment is outside the qualified profile")
                    label?.let { requireSupportedText(it.value.text) }
                    val marker = label?.let { measurer.measure(it.value.text, it.style) }
                    val markerAnchor = format.leftIndent + format.firstLineIndent
                    val markerLeft = markerAnchor - (marker?.width ?: 0f) * when (label?.alignment) { PdfAlignment.RIGHT -> 1f; PdfAlignment.CENTER -> .5f; else -> 0f }
                    val markerEnd = markerLeft + (marker?.width ?: 0f)
                    // Flow measurement uses local coordinates. Word places a negative left-aligned marker
                    // outside the paragraph and resumes the item at its indent when the marker fits before it.
                    val marginMarker = permitMarginMarker && lineArea == null && format.textDirection == null &&
                        format.alignment == PdfAlignment.LEFT && label?.alignment == PdfAlignment.LEFT &&
                        label.suffix == PdfListSuffix.TAB && markerLeft < -.001f && markerEnd <= format.leftIndent + .001f
                    if (label != null && markerLeft < -.001f && !marginMarker)
                        overflow("List marker exceeds left boundary", -markerLeft, 0f)
                    if (label != null && markerEnd > width - format.rightIndent + .001f) overflow("List marker exceeds right boundary", markerEnd, width - format.rightIndent)
                    val firstTextLeft = if (label == null) format.leftIndent + format.firstLineIndent else when (label.suffix) {
                        PdfListSuffix.NOTHING -> markerEnd
                        PdfListSuffix.SPACE -> markerEnd + measurer.measure(" ", label.style).width
                        PdfListSuffix.TAB -> if (marginMarker) format.leftIndent else label.tabStop?.takeIf { it > markerEnd + .001f }
                            ?: ((kotlin.math.floor(markerEnd / label.defaultTabInterval) + 1) * label.defaultTabInterval)
                    }
                    top += ParagraphSpacing.before(block, previousAfter, top <= page.margins.top + .001f)
                    val frame = ParagraphLines.frame(block, document.blocks.getOrNull(blockIndex - 1) as? PdfParagraph, document.blocks.getOrNull(blockIndex + 1) as? PdfParagraph)
                    top += frame.before
                    val paragraphTop = top
                    val paragraphElementStart = elements.size
                    val paragraphLineStart = lineBoxes.size
                    var firstLine = true
                    var lineIndex = 0

                    fun area() = lineArea?.invoke(lineIndex) ?: (0f to width)

                    fun lineLeft(): Float {
                        val desired = if (firstLine) firstTextLeft else format.leftIndent
                        return if (lineArea == null) desired else maxOf(area().first, desired)
                    }

                    var rightTabEdge: Float? = null

                    fun lineEdge(): Float {
                        val paragraphEdge = width - format.rightIndent
                        return if (lineArea == null) paragraphEdge else minOf(area().first + area().second, paragraphEdge)
                    }

                    fun availableWidth(): Float {
                        val edge = lineEdge()
                        return maxOf(edge, rightTabEdge ?: edge) - lineLeft()
                    }

                    if (availableWidth() <= 0) overflow("Line start leaves no content width", lineLeft(), lineLeft() + availableWidth())
                    if (width - format.leftIndent - format.rightIndent <= 0) overflow("Paragraph indents leave no content width", format.leftIndent + format.rightIndent, width)
                    var line = mutableListOf<Fragment>()
                    var lineWidth = 0f
                    var lineContainsAnchoredTab = false
                    val spaces = mutableListOf<Fragment>()
                    val groups = words(block.runs, measurer, format.textDirection != null)
                    val paragraphBidi = format.textDirection?.let { direction ->
                        val text = block.runs.joinToString("") { when (it) { is PdfTextRun -> it.text; is PdfLineBreak -> "\n"; else -> "" } }
                        java.text.Bidi(text, if (direction == PdfTextDirection.RIGHT_TO_LEFT) java.text.Bidi.DIRECTION_RIGHT_TO_LEFT else java.text.Bidi.DIRECTION_LEFT_TO_RIGHT)
                    }

                    fun flush(justify: Boolean = false, terminal: Boolean = false) {
                        if (line.isEmpty()) return
                        val deferredListLabel = label != null && line.all { it.lineBreak != null } && line.any { it.pageBreak }
                        if (justify && lineContainsAnchoredTab && format.alignment == PdfAlignment.JUSTIFY)
                            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Right, centered or decimal tabs in non-final justified lines are not qualified")
                        val automatic = if (automaticSpacing) {
                            val lineRuns = line.filter { it.lineBreak == null }.ifEmpty { line }.map { fragment ->
                                fragment.lineBreak ?: fragment.vector ?: fragment.image?.let { image ->
                                    PdfImageRun(image).withPadding(fragment.imagePadding ?: PdfMargins(0f, 0f, 0f, 0f)).withLineClipping(fragment.imageClipToLine)
                                } ?: PdfTextRun(fragment.text, requireNotNull(fragment.style))
                            }
                            val lineParagraph = block.copy(runs = lineRuns, listLabel = block.listLabel.takeIf { firstLine })
                            automaticBox(lineParagraph, measurer)
                        } else null
                        val lineStart = elements.size
                        val metricLine = if (automatic != null) line.filter { it.image != null || it.vector != null ||
                            it.lineBreak != null || it.text.any { character -> character != ' ' } }.ifEmpty { line } else line
                        val ascent = maxOf(metricLine.maxOf { if (automatic == null) it.metrics.ascent else it.metrics.naturalAscent }, if (firstLine) (if (automatic == null) marker?.ascent else marker?.naturalAscent) ?: 0f else 0f)
                        val descent = maxOf(metricLine.maxOf { if (automatic == null) it.metrics.descent else it.metrics.naturalDescent }, if (firstLine) (if (automatic == null) marker?.descent else marker?.naturalDescent) ?: 0f else 0f)
                        val height = automatic?.first ?: format.exactLineHeight ?: (ascent + descent) * 1.2f
                        if (top + height > bottom) overflow("Line box exceeds remaining area height", height, bottom - top)
                        val baseline = top + (automatic?.second ?: format.exactLineHeight?.times(0.8f) ?: ascent)

                        fun background(style: PdfTextStyle, advance: Float, offset: Float = 0f): TextBackground? =
                            style.background?.takeIf { advance > 0f }?.let { TextBackground(advance, baseline - top, height, offset) }

                        val paddedInlineImages = format.exactLineHeight != null && block.runs.any {
                            it is PdfImageRun && (it.padding != PdfMargins(0f, 0f, 0f, 0f) || it.clipToLine)
                        } && block.runs.all { it is PdfTextRun || it is PdfTab || it is PdfLineBreak || it is PdfPageBreak || it is PdfImageRun }
                        val whitespaceInk = automatic != null && line.any { it.image == null && it.vector == null && it.lineBreak == null &&
                            it.text.all { character -> character == ' ' } && (it.metrics.naturalAscent > ascent || it.metrics.naturalDescent > descent) }
                        val deferInkBounds = paddedInlineImages || whitespaceInk || (measurement && format.exactLineHeight != null && !automaticSpacing ||
                            (format.automaticLineSpacing?.multiple ?: 1f) < 1f) &&
                            block.runs.all { it is PdfTextRun || it is PdfTab || it is PdfLineBreak }
                        if (!deferInkBounds && baseline - ascent < page.margins.top - 0.01f) overflow("Line ascent exceeds space above baseline", ascent, baseline - page.margins.top)
                        if (!deferInkBounds && baseline + descent > bottom + 0.01f) overflow("Line descent exceeds space below baseline", descent, bottom - baseline)
                        val remaining = availableWidth() - lineWidth
                        val prefix = (label?.value?.text?.length ?: 0) + if (label == null || label.suffix == PdfListSuffix.NOTHING) 0 else 1
                        if (firstLine && label != null && !deferredListLabel) elements += PositionedText(label.value.text, label.style, page.margins.left + area().first + markerLeft, baseline, logicalOwner = block.semantics?.id, span = block.semantics?.let { PdfSpanSemantics(semanticChildId(it.id, "label"), PdfSemanticRole.LABEL) }, logicalOffset = block.semantics?.let { 0 }, requiresPhysicalBounds = deferInkBounds, background = background(label.style, if (label.highlightSuffix) firstTextLeft - markerLeft else requireNotNull(marker).width),
                            physicalLeftOverhang = (-markerLeft).takeIf { marginMarker })
                        var x = page.margins.left + lineLeft() + when (format.alignment) {
                            PdfAlignment.LEFT, PdfAlignment.JUSTIFY -> 0f
                            PdfAlignment.CENTER -> remaining / 2
                            PdfAlignment.RIGHT -> remaining
                        }
                        val lineStartX = x
                        val lastTab = line.indexOfLast { it.tab != null }
                        val firstContent = line.indices.firstOrNull { index ->
                            index > lastTab && line[index].lineBreak == null && line[index].text.any { it != ' ' }
                        } ?: line.size
                        val lastContent = line.indexOfLast { it.lineBreak == null && it.text.any { char -> char != ' ' } }
                        val stretch = if (justify && format.alignment == PdfAlignment.JUSTIFY && remaining > 0f)
                            line.mapIndexed { index, fragment -> if (index > firstContent && index < lastContent &&
                                fragment.text.all { it == ' ' }) fragment.text.length else 0 } else List(line.size) { 0 }
                        val spaceCount = stretch.sum()
                        val extraPerSpace = if (spaceCount == 0) 0f else remaining / spaceCount
                        if (line.all { it.lineBreak != null }) {
                            elements += PositionedText("", requireNotNull(line.first().style), x, baseline)
                        } else if (paragraphBidi != null) {
                            val slices = line.filter { it.lineBreak == null }.map { LogicalTextSlice(it.text, requireNotNull(it.style), it.sourceStart) }
                            for (run in BidiLine.visualRuns(paragraphBidi, slices)) {
                                elements += PositionedText(run.text, run.style, x, baseline, logicalOwner = block.semantics?.id, logicalOffset = block.semantics?.let { run.sourceStart + prefix }, requiresPhysicalBounds = deferInkBounds,
                                    background = background(run.style, measurer.measure(run.text, run.style).width))
                                x += measurer.measure(run.text, run.style).width
                            }
                        } else for ((fragmentIndex, fragment) in line.withIndex()) {
                            if (fragment.lineBreak != null) continue
                            val extra = stretch[fragmentIndex] * extraPerSpace
                            if (fragment.vector != null) elements += PositionedVector(fragment.vector, x, baseline - fragment.vector.height)
                            else if (fragment.image != null) elements += PositionedImage(fragment.image,
                                x + (fragment.imagePadding?.left ?: 0f), baseline - fragment.image.height - (fragment.imagePadding?.bottom ?: 0f),
                                requiresPhysicalBounds = deferInkBounds || fragment.imagePadding?.let { it != PdfMargins(0f, 0f, 0f, 0f) } == true || fragment.imageClipToLine,
                                clip = if (fragment.imageClipToLine && format.exactLineHeight != null)
                                    RectangleClip(x + (fragment.imagePadding?.left ?: 0f), top,
                                        x + (fragment.imagePadding?.left ?: 0f) + fragment.image.width, top + height) else null)
                            else {
                                val style = requireNotNull(fragment.style)
                                val tabUnderline = fragment.tab != null && style.underline
                                val displayStyle = if (tabUnderline) style.copy(underline = false) else style
                                elements += PositionedText(fragment.text, displayStyle, x + fragment.paintOffset, baseline,
                                    fragment.decoration, fragment.navigation, block.semantics?.id, fragment.semantics,
                                    block.semantics?.takeUnless { fragment.decoration || fragment.text.isEmpty() }?.let { fragment.sourceStart + prefix },
                                    trailingDecorationWidth = if (extra > 0f) fragment.metrics.width + extra else null,
                                    requiresPhysicalBounds = deferInkBounds || (rightTabEdge ?: width) > width + .001f,
                                    background = background(style, fragment.metrics.width + extra, -fragment.paintOffset),
                                    tabLinkArea = fragment.tab?.navigation?.let { TabLinkArea(-fragment.paintOffset, fragment.metrics.width) })
                                if (tabUnderline && fragment.metrics.width > 0f) {
                                    elements += PositionedText("", style.withStrikethrough(false), x, baseline, decoration = true,
                                        trailingDecorationWidth = fragment.metrics.width, requiresPhysicalBounds = deferInkBounds)
                                }
                            }
                            x += fragment.metrics.width + extra
                        }
                        if (terminal && block.endDestinations.isNotEmpty()) {
                            val blank = line.all { it.text.isBlank() && it.tab == null && it.image == null && it.vector == null }
                            elements += PositionedDestination(block.endDestinations, if (blank) lineStartX else x, top)
                        }
                        if (block.underlineTrailingSpaces == true) {
                            if (paragraphBidi != null && spaces.any { it.style?.let { style -> style.underline || style.strikethrough } == true })
                                throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Bidi trailing-space decoration is not qualified")
                            val edge = page.margins.left + lineLeft() + availableWidth()
                            for (space in spaces) {
                                val length = minOf(space.metrics.width, maxOf(0f, edge - x))
                                if (space.style?.let { it.underline || it.strikethrough } == true && length > 0f)
                                    elements += PositionedText(" ", requireNotNull(space.style), x, baseline, decoration = true, trailingDecorationWidth = length, requiresPhysicalBounds = deferInkBounds)
                                x += space.metrics.width
                            }
                        }
                        lineBoxes += MeasuredLineBox(lineStart, elements.size, top, height, line.any { it.pageBreak })
                        top += height
                        line = mutableListOf()
                        lineWidth = 0f
                        lineContainsAnchoredTab = false
                        rightTabEdge = null
                        firstLine = firstLine && deferredListLabel
                        lineIndex++
                    }

                    fun placeSoftHyphenWord(fragments: List<Fragment>): List<Fragment> {
                        if (fragments.none { it.discretionaryHyphen }) return fragments
                        val lastText = fragments.indexOfLast { !it.discretionaryHyphen }
                        var start = 0
                        var remaining = fragments.sumOf { it.metrics.width.toDouble() }.toFloat()
                        while (start <= lastText) {
                            checkCancellation()
                            val spacesWidth = spaces.sumOf { it.metrics.width.toDouble() }.toFloat()
                            val room = availableWidth() - lineWidth - spacesWidth
                            if (remaining <= room + .001f) break
                            var width = 0f
                            var prefixWidth = 0f
                            var selected = -1
                            var hyphen: Fragment? = null
                            for (index in start until lastText) {
                                checkCancellation()
                                val fragment = fragments[index]
                                width += fragment.metrics.width
                                if (width > room + .001f) break
                                if (!fragment.discretionaryHyphen || width <= 0f) continue
                                val paint = measurer.measure("-", requireNotNull(fragment.style))
                                if (width + paint.width <= room + .001f) {
                                    selected = index
                                    prefixWidth = width
                                    hyphen = fragment.copy(text = "-", metrics = paint)
                                }
                            }
                            if (hyphen == null) {
                                if (line.isEmpty()) break
                                flush(justify = true)
                                spaces.clear()
                                continue
                            }
                            line += spaces
                            spaces.clear()
                            line += fragments.subList(start, selected).filterNot { it.discretionaryHyphen }
                            line += hyphen
                            lineWidth += spacesWidth + prefixWidth + hyphen.metrics.width
                            flush(justify = true)
                            remaining -= prefixWidth
                            start = selected + 1
                        }
                        return fragments.subList(start, fragments.size).filterNot { it.discretionaryHyphen }
                    }

                    fun ordinaryWebAddress(word: List<Fragment>): Boolean = lineArea == null && label == null &&
                        format.textDirection == null && block.runs.none { it is PdfTab } && word.all {
                            it.style != null && !it.style.fontFeatures.active && it.style.characterSpacing == 0f &&
                                it.navigation?.destinations?.isNotEmpty() != true
                        }

                    fun placeWebAddress(word: List<Fragment>, wordWidth: Float, spaceWidth: Float): Boolean {
                        if (lineWidth + spaceWidth + wordWidth <= availableWidth() + .001f || !ordinaryWebAddress(word)) return false
                        val runs = word.map { it.text to requireNotNull(it.style) }
                        val breaks = WebAddressBreaks.of(runs, measurer) ?: return false
                        val link = word.first().navigation?.link as? PdfLink.External
                        val displaysTarget = link != null && word.all { it.navigation?.link == link } &&
                            runs.joinToString("") { it.first } == link.uri
                        var gap = spaceWidth
                        while (breaks.hasMore) {
                            val available = availableWidth() - lineWidth - gap
                            val preferred = breaks.take(available)
                            val slices = if (preferred.isEmpty() && line.isEmpty() && displaysTarget)
                                breaks.takeCharacters(available) else preferred
                            if (slices.isEmpty()) {
                                if (line.isEmpty())
                                    overflow("Web address exceeds line width at a supported break", breaks.minimumWidth(), availableWidth() - gap)
                                flush(justify = true)
                                spaces.clear()
                                gap = 0f
                                continue
                            }
                            line += spaces
                            spaces.clear()
                            val parts = slices.map { slice -> word[slice.run].withSlice(slice) }
                            line += parts
                            lineWidth += gap + parts.sumOf { it.metrics.width.toDouble() }.toFloat()
                            gap = 0f
                            if (breaks.hasMore) flush(justify = true)
                        }
                        return true
                    }

                    for ((groupIndex, originalWord) in groups.withIndex()) {
                        val word = placeSoftHyphenWord(originalWord)
                        if (word.isEmpty()) continue
                        checkCancellation()
                        val lineBreak = word.singleOrNull()?.lineBreak
                        if (lineBreak != null) {
                            line += spaces
                            lineWidth += spaces.sumOf { it.metrics.width.toDouble() }.toFloat()
                            spaces.clear()
                            line += word
                            flush(justify = !word.single().pageBreak)
                            continue
                        }
                        val control = word.singleOrNull()?.tab
                        if (control != null) {
                            if (format.alignment !in setOf(PdfAlignment.LEFT, PdfAlignment.JUSTIFY))
                                throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Tab paragraphs require left alignment or qualified justification")
                            line += spaces; lineWidth += spaces.sumOf { it.metrics.width.toDouble() }.toFloat(); spaces.clear()
                            val settings = format.tabs ?: PdfTabSettings()
                            var cursor = lineLeft() + lineWidth
                            val following = groups.drop(groupIndex + 1).takeWhile { group -> group.none { it.tab != null || it.lineBreak != null } }.flatten()
                            val payloadWidth = following.sumOf { it.metrics.width.toDouble() }.toFloat()
                            var decimalWidth = 0f
                            var decimalPrefix = DecimalTabs.prefixLength(following.joinToString("") { it.text }, settings)
                            for (fragment in following) {
                                if (decimalPrefix < fragment.text.length) {
                                    if (decimalPrefix > 0) decimalWidth += measurer.measure(fragment.text.take(decimalPrefix), requireNotNull(fragment.style)).width
                                    break
                                }
                                decimalWidth += fragment.metrics.width
                                decimalPrefix -= fragment.text.length
                            }
                            val explicit = settings.stops.firstOrNull { it.position > cursor + .001f }
                            val position = explicit?.position
                                ?: (kotlin.math.floor((cursor + .001f) / settings.defaultInterval) + 1) * settings.defaultInterval
                            val alignment = explicit?.alignment ?: PdfTabAlignment.LEFT
                            val leader = explicit?.leader ?: PdfTabLeader.NONE
                            val paragraphEdge = width - format.rightIndent
                            val areaEdge = area().let { (left, span) -> minOf(width, left + span) }
                            val useRightEdge = position > width + .001f && alignment != PdfTabAlignment.LEFT
                            val alignedStart = if (useRightEdge) {
                                lineEdge() - payloadWidth
                            } else {
                                position - when (alignment) {
                                    PdfTabAlignment.LEFT -> 0f
                                    PdfTabAlignment.RIGHT -> payloadWidth
                                    PdfTabAlignment.CENTER -> payloadWidth / 2
                                    PdfTabAlignment.DECIMAL -> decimalWidth
                                }
                            }
                            var fieldEdge = when {
                                useRightEdge -> lineEdge()
                                alignment == PdfTabAlignment.RIGHT && position <= areaEdge + .001f ->
                                    maxOf(paragraphEdge, position)
                                else -> paragraphEdge
                            }
                            // Word shifts a centered or decimal field left when its full text fits between the cursor and the edge.
                            // Keep preceding content and continue to reject fields that cannot fit in the available interval.
                            val lastStart = fieldEdge - payloadWidth
                            fun startAtCursor(): Float {
                                val ordinary = format.alignment == PdfAlignment.LEFT &&
                                    alignment in setOf(PdfTabAlignment.CENTER, PdfTabAlignment.DECIMAL)
                                val candidate = if (ordinary && lastStart >= cursor - .001f)
                                    minOf(alignedStart, lastStart) else alignedStart
                                return maxOf(cursor, candidate)
                            }
                            var start = startAtCursor()
                            val anchored = alignment != PdfTabAlignment.LEFT
                            if (anchored && start + payloadWidth > fieldEdge + .001f &&
                                explicit != null && format.alignment == PdfAlignment.LEFT && lineArea == null &&
                                following.all { it.style != null } && line.any { it.text.isNotBlank() }) {
                                if (useRightEdge) {
                                    // An explicit right stop beyond the text area can continue into the physical right margin.
                                    if (alignment == PdfTabAlignment.RIGHT && rightMarginAllowance > 0f &&
                                        position <= width + rightMarginAllowance + .001f) {
                                        fieldEdge = minOf(width + rightMarginAllowance, start + payloadWidth)
                                    }
                                } else {
                                    // An in-bounds stop can move its intact field to the following line.
                                    flush(justify = false)
                                    cursor = lineLeft()
                                    start = startAtCursor()
                                }
                            }
                            lineContainsAnchoredTab = lineContainsAnchoredTab || anchored
                            // An unpainted final left tab beyond the text edge retains its line metrics without adding width.
                            if (start > fieldEdge + .001f && !anchored && groupIndex == groups.lastIndex &&
                                cursor <= fieldEdge + .001f &&
                                leader == PdfTabLeader.NONE && control.navigation == null &&
                                !control.style.underline && !control.style.strikethrough && control.style.background == null) {
                                start = cursor
                            }
                            if (start > fieldEdge + .001f) overflow("Tab start exceeds line boundary", start, fieldEdge)
                            if (alignment != PdfTabAlignment.LEFT && start + payloadWidth > fieldEdge + .001f)
                                overflow("Aligned tab content exceeds line boundary", start + payloadWidth, fieldEdge)
                            if (fieldEdge > paragraphEdge) rightTabEdge = maxOf(rightTabEdge ?: paragraphEdge, fieldEdge)
                            val gap = maxOf(0f, start - cursor)
                            val symbol = when (leader) { PdfTabLeader.NONE -> " "; PdfTabLeader.DOT -> "."; PdfTabLeader.HYPHEN -> "-"; PdfTabLeader.UNDERSCORE -> "_" }
                            val metrics = measurer.measure(symbol, control.style)
                            val count = if (leader == PdfTabLeader.NONE) 1 else kotlin.math.floor(gap / metrics.width).toInt()
                            if (count > 4096) throw PdfRenderException(PdfFailureCode.RESOURCE_LIMIT, "Tab leader budget exceeded")
                            val text = if (count > 0) symbol.repeat(count) else " "
                            val offset = if (leader == PdfTabLeader.NONE || count == 0) 0f else (gap - count * metrics.width) / 2
                            line += Fragment(text, control.style, metrics.copy(width = gap), tab = control,
                                navigation = control.navigation, paintOffset = offset, decoration = leader != PdfTabLeader.NONE)
                            lineWidth += gap
                            continue
                        }
                        if (word.all { it.text.all { character -> character == ' ' } }) {
                            spaces += word
                            continue
                        }
                        val wordWidth = word.sumOf { it.metrics.width.toDouble() }.toFloat()
                        val spaceWidth = spaces.sumOf { it.metrics.width.toDouble() }.toFloat()
                        if (!block.breakLongWords && placeWebAddress(word, wordWidth, spaceWidth)) continue
                        val oversizedWord = wordWidth > availableWidth() + .001f
                        val simpleWebPath = oversizedWord && !block.breakLongWords && line.isEmpty() && spaces.isEmpty() &&
                            ordinaryWebAddress(word) && WebAddressBreaks.isSimplePath(word.map { it.text })
                        if (oversizedWord && (block.breakLongWords || simpleWebPath)) {
                            if (format.textDirection != null || word.any { it.style == null || it.text.isEmpty() })
                                throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Long-word breaking with bidi or inline objects is not qualified")
                            if (line.isNotEmpty()) {
                                flush(justify = true)
                                spaces.clear()
                            }
                            val leadingWidth = spaces.sumOf { it.metrics.width.toDouble() }.toFloat()
                            if (leadingWidth >= availableWidth())
                                overflow("Word and leading spaces exceed line width", leadingWidth + wordWidth, availableWidth())
                            line += spaces
                            lineWidth += leadingWidth
                            spaces.clear()
                            val breaks = LongWordBreaks(word.map { it.text to requireNotNull(it.style) }, measurer)
                            while (breaks.hasMore) {
                                checkCancellation()
                                val parts = breaks.next(availableWidth() - lineWidth).map { slice -> word[slice.run].withSlice(slice) }
                                line += parts
                                lineWidth += parts.sumOf { it.metrics.width.toDouble() }.toFloat()
                                if (breaks.hasMore) flush(justify = true)
                            }
                            continue
                        }
                        if (lineWidth + spaceWidth + wordWidth > availableWidth() + .001f) {
                            if (line.isEmpty()) overflow("Word and leading spaces exceed line width", spaceWidth + wordWidth, availableWidth())
                            flush(justify = true)
                            spaces.clear()
                        }
                        if (wordWidth > availableWidth() + .001f) overflow("Word width exceeds line width", wordWidth, availableWidth())
                        line += spaces
                        lineWidth += spaces.sumOf { it.metrics.width.toDouble() }.toFloat()
                        spaces.clear()
                        line += word
                        lineWidth += wordWidth
                    }
                    groups.lastOrNull()?.singleOrNull()?.takeIf { it.lineBreak != null && !it.pageBreak }?.let { last ->
                        val mark = block.paragraphMarkStyle ?: requireNotNull(last.style)
                        line += last.copy(style = mark, lineBreak = PdfLineBreak(mark))
                    }
                    if (line.isEmpty() && spaces.isNotEmpty()) {
                        if (block.underlineTrailingSpaces == null) {
                            if (spaces.sumOf { it.metrics.width.toDouble() } > availableWidth())
                                overflow("Whitespace exceeds line width", spaces.sumOf { it.metrics.width.toDouble() }.toFloat(), availableWidth())
                            line += spaces
                            lineWidth = spaces.sumOf { it.metrics.width.toDouble() }.toFloat()
                        } else {
                            line += spaces.map { space -> space.copy(text = " ",
                                style = requireNotNull(space.style).copy(underline = false).withStrikethrough(false),
                                metrics = space.metrics.copy(width = 0f)) }
                        }
                    }
                    flush(terminal = true)
                    val strokes = mutableListOf<PositionedElement>()
                    ParagraphLines.strokes(block, frame, width, paragraphTop, top - paragraphTop, true, true, strokes)
                    val decorations = strokes.map { FlowLayouter.translate(it, page.margins.left, 0f) }
                    val fills = decorations.filterIsInstance<PositionedRectangle>().filter { it.paragraph != null }
                    if (fills.isNotEmpty()) {
                        elements.addAll(paragraphElementStart, fills)
                        for (index in paragraphLineStart until lineBoxes.size) {
                            val box = lineBoxes[index]
                            lineBoxes[index] = box.copy(from = box.from + fills.size, until = box.until + fills.size)
                        }
                    }
                    elements += decorations.filterNot { it is PositionedRectangle && it.paragraph != null }
                    val remainingAfterContent = bottom - top
                    top += frame.after
                    if (top > bottom + .001f) overflow("Paragraph border spacing exceeds remaining height", frame.after, remainingAfterContent)
                    top += block.spaceAfter
                    previousAfter = block.spaceAfter
                    for (index in paragraphStart until elements.size) elements[index] = ParagraphPageOffsets.mark(elements[index], block.pageOffset)
                }
            }
        }
        return PageLayout(page, ParagraphLines.connect(elements), lineBoxes.toList())
    }

    internal fun automaticBox(p: PdfParagraph, measurer: TextMeasurer, allowMixed: Boolean = true): Pair<Float, Float>? {
        val minimumHeight = p.minimumLineHeight
        val multiple = p.format.automaticLineSpacing?.multiple ?: if (minimumHeight != null) 1f else return null
        val vectorsOnly = p.runs.all { it is PdfVectorRun }
        val objectHeight = (p.runs.singleOrNull() as? PdfImageRun)?.let { it.image.height + it.padding.top + it.padding.bottom } ?:
            p.runs.takeIf { vectorsOnly }?.filterIsInstance<PdfVectorRun>()?.maxOfOrNull { it.height }
        if (objectHeight != null) {
            val minimum = p.minimumLineStyle?.let { measurer.measure("", it) }
            val fontBaseline = (minimum?.naturalAscent ?: 0f) + (minimum?.lineGap ?: 0f)
            // Word places a standalone inline shape at the bottom of the paragraph-mark box.
            // A paragraph with visible text retains its ordinary text baseline below.
            val baseline = maxOf(objectHeight, fontBaseline + if (vectorsOnly) minimum?.naturalDescent ?: 0f else 0f)
            // An unprinted paragraph mark supplies a minimum box, not extra descent below a taller image.
            val naturalHeight = maxOf(baseline, fontBaseline + (minimum?.naturalDescent ?: 0f))
            val height = maxOf(naturalHeight * multiple, minimumHeight ?: 0f)
            checkAutomaticHeight(height, naturalHeight)
            return height to (baseline + if (minimumHeight != null) height - naturalHeight else 0f)
        }
        val metricRuns = if (p.runs.any { it is PdfTextRun && it.text.any { character -> character != ' ' } })
            p.runs.filterNot { it is PdfTextRun && it.text.all { character -> character == ' ' } } else p.runs
        val styles = metricRuns.mapNotNull { when (it) {
            is PdfVectorRun -> null
            is PdfTextRun -> it.style; is PdfTab -> it.style; is PdfLineBreak -> it.style; is PdfPageBreak -> it.style; is PdfPageField -> it.style
            else -> throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Automatic inline image spacing is not qualified")
        } } + listOfNotNull(p.listLabel?.style)
        val metrics = styles.map { measurer.measure("", it) }
        if (metrics.any { !it.naturalAscent.isFinite() || !it.naturalDescent.isFinite() || !it.lineGap.isFinite() })
            throw PdfRenderException(PdfFailureCode.INVALID_RESOURCE, "Natural font line metrics are unavailable")
        if (!allowMixed) {
            val ordinary = metrics.filterIndexed { index, _ -> styles[index].baselineShift == 0f }.ifEmpty { metrics }
            if (ordinary.map { Triple(it.naturalAscent, it.naturalDescent, it.lineGap) }.distinct().size > 1)
                throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Mixed bidi natural line boxes are not qualified")
        }
        // The caller supplies the actual physical line. Never reuse a paragraph-wide maximum
        // when wrapped lines contain different sizes, faces or raised note markers.
        val minimum = p.minimumLineStyle?.let { measurer.measure("", it) }
        if (minimum != null && (!minimum.naturalAscent.isFinite() || !minimum.naturalDescent.isFinite() || !minimum.lineGap.isFinite()))
            throw PdfRenderException(PdfFailureCode.INVALID_RESOURCE, "Minimum line metrics are unavailable")
        val all = metrics + listOfNotNull(minimum)
        val vectorHeight = p.runs.filterIsInstance<PdfVectorRun>().maxOfOrNull { it.height } ?: 0f
        val ascent = maxOf(all.maxOfOrNull { it.naturalAscent + it.lineGap } ?: 0f, vectorHeight)
        val descent = all.maxOfOrNull { it.naturalDescent } ?: 0f
        val naturalHeight = ascent + descent
        val height = maxOf(naturalHeight * multiple, minimumHeight ?: 0f)
        checkAutomaticHeight(height, naturalHeight * minOf(1f, multiple))
        return height to (ascent * minOf(1f, multiple) + if (minimumHeight != null) height - naturalHeight else 0f)
    }

    internal fun unwrappedTextWidth(runs: List<PdfInline>, measurer: TextMeasurer): Float =
        maximumLineWidth(words(runs, measurer, false))

    internal fun intrinsicWidths(paragraph: PdfParagraph, measurer: TextMeasurer): Pair<Float, Float> {
        if (paragraph.anchoredImages.isNotEmpty())
            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Automatic table widths with tabs or floating images are not qualified")
        val runs = if (paragraph.runs.any { it is PdfTab }) automaticTableTextRuns(paragraph) else paragraph.runs
        val groups = words(runs, measurer, paragraph.format.textDirection != null)
        if (groups.any { group -> group.any { it.discretionaryHyphen } })
            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Automatic table widths with discretionary hyphens are not qualified")
        val widths = groups.map { group -> group.sumOf { it.metrics.width.toDouble() }.toFloat() }
        val minimum = groups.indices.filter { index -> groups[index].any { fragment -> fragment.text.any { it != ' ' } } }.maxOfOrNull { widths[it] } ?: 0f
        val indent = paragraph.format.leftIndent + paragraph.format.rightIndent + maxOf(0f, paragraph.format.firstLineIndent)
        val label = paragraph.listLabel?.let { measurer.measure(it.value.text, it.style).width } ?: 0f
        return maxOf(0f, minimum + indent + label) to maxOf(0f, maximumLineWidth(groups) + indent + label)
    }

    private fun automaticTableTextRuns(paragraph: PdfParagraph): List<PdfInline> {
        val runs = paragraph.runs.dropLastWhile { it is PdfTab }
        val lastTextIndex = runs.indexOfLast { it !is PdfTextRun || it.text.isNotBlank() }
        val last = runs.getOrNull(lastTextIndex) as? PdfTextRun
        val format = paragraph.format
        // Plain terminal default tabs add no intrinsic printable content.
        // Preserve the controls and intervening spaces for the resolved line's width check.
        if (last == null || format.alignment != PdfAlignment.LEFT || format.textDirection != null ||
            !format.tabs?.stops.isNullOrEmpty() || runs.any { it is PdfTab } || last.style.underline ||
            last.style.strikethrough || last.style.background != null ||
            runs.subList(lastTextIndex + 1, runs.size).any { (it as PdfTextRun).style != last.style } ||
            paragraph.runs.subList(runs.size, paragraph.runs.size).any { (it as PdfTab).style != last.style })
            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Automatic table widths support only undecorated terminal default tabs")
        return runs
    }

    private fun maximumLineWidth(groups: List<List<Fragment>>): Float {
        var line = 0f
        var widest = 0f
        for (group in groups) {
            checkCancellation()
            if (group.any { it.lineBreak != null }) {
                widest = maxOf(widest, line)
                line = 0f
            } else line += group.sumOf { it.metrics.width.toDouble() }.toFloat()
        }
        return maxOf(widest, line)
    }

    private data class Fragment(val text: String, val style: PdfTextStyle?, val metrics: TextMetrics, val image: PdfImage? = null, val tab: PdfTab? = null, val paintOffset: Float = 0f, val decoration: Boolean = false, val sourceStart: Int = 0, val navigation: PdfTextNavigation? = null, val semantics: PdfSpanSemantics? = null, val vector: PdfVectorRun? = null, val lineBreak: PdfLineBreak? = null, val pageBreak: Boolean = false, val imagePadding: PdfMargins? = null, val imageClipToLine: Boolean = false) {
        val discretionaryHyphen: Boolean get() = text.length == 1 && text[0] == softHyphen

        fun withSlice(slice: TextRunSlice) = copy(
            text = text.substring(slice.from, slice.until), metrics = slice.metrics, sourceStart = sourceStart + slice.from,
        )
    }

    private fun words(runs: List<PdfInline>, measurer: TextMeasurer, bidi: Boolean): List<List<Fragment>> {
        val groups = mutableListOf<MutableList<Fragment>>()
        var sourceOffset = 0
        var previousSpace: Boolean? = null
        var sawSoftHyphen = false
        for (run in shapingRuns(runs)) {
            if (run is PdfPageField) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Page fields require page-band resolution")
            if (run is PdfReferenceField || run is PdfSequenceField) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "References require document-wide resolution")
            if (run is PdfNoteCall || run is PdfNoteLabel) throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Notes require document-wide resolution")
            if (run is PdfLineBreak || run is PdfPageBreak) {
                val style = if (run is PdfLineBreak) run.style else (run as PdfPageBreak).style
                measurer.measure("", style)
                groups += mutableListOf(Fragment("\n", style, TextMetrics(0f, 0f, 0f), sourceStart = sourceOffset,
                    lineBreak = PdfLineBreak(style), pageBreak = run is PdfPageBreak))
                previousSpace = null
                sourceOffset++
                continue
            }
            if (run is PdfTab) {
                groups += mutableListOf(Fragment("\t", run.style, TextMetrics(0f, 0f, 0f), tab = run))
                previousSpace = null
                sourceOffset++
                continue
            }
            if (run is PdfVectorRun) {
                groups += mutableListOf(Fragment("\uFFFC", null, TextMetrics(run.width, run.height, 0f), vector = run))
                previousSpace = null
                sourceOffset++
                continue
            }
            if (run is PdfImageRun) {
                groups += mutableListOf(Fragment("\uFFFC", null, TextMetrics(run.image.width + run.padding.left + run.padding.right,
                    run.image.height + run.padding.top + run.padding.bottom, 0f), run.image, imagePadding = run.padding, imageClipToLine = run.clipToLine))
                previousSpace = null
                sourceOffset++
                continue
            }
            run as PdfTextRun
            if (softHyphen in run.text && (bidi || runs.any { it is PdfTextRun && it.style.fontFeatures.active } ||
                    runs.any { it is PdfTab } || run.navigation?.destinations?.isNotEmpty() == true))
                throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Discretionary hyphens with bidi, tabs, shaping or destinations are not qualified")
            requireSupportedText(if (softHyphen in run.text) run.text.replace(softHyphen.toString(), "") else run.text, bidi)
            for (match in wordFragments.findAll(run.text)) {
                val space = match.value[0] == ' '
                if (space != previousSpace) groups += mutableListOf<Fragment>()
                val discretionary = match.value.length == 1 && match.value[0] == softHyphen
                if (discretionary) sawSoftHyphen = true
                val metrics = if (discretionary) TextMetrics(0f, 0f, 0f) else measurer.measure(match.value, run.style)
                groups.last() += Fragment(match.value, run.style, metrics, sourceStart = sourceOffset + match.range.first, navigation = run.navigation, semantics = run.semantics)
                previousSpace = space
            }
            sourceOffset += run.text.length
        }
        for (index in 1 until groups.lastIndex) {
            val gap = groups[index].singleOrNull() ?: continue
            val style = gap.style ?: continue
            if (!style.fontFeatures.active || gap.text.any { it != ' ' }) continue
            val previous = groups[index - 1].lastOrNull() ?: continue
            val next = groups[index + 1].firstOrNull() ?: continue
            if (previous.style == style && next.style == style && previous.tab == null && next.tab == null && previous.lineBreak == null && next.lineBreak == null) {
                groups[index][0] = gap.copy(metrics = measurer.gap(previous.text, gap.text, next.text, style))
            }
        }
        if (!sawSoftHyphen) return groups
        val printable = groups.filterNot { group -> group.all { it.discretionaryHyphen } }
        if (printable.isNotEmpty()) return printable

        // A paragraph of invisible controls still contributes a blank line.
        val first = groups.first().first()
        val style = requireNotNull(first.style).copy(underline = false).withStrikethrough(false).withBackground(null)
        return listOf(listOf(Fragment(" ", style, measurer.measure("", style))))
    }

    private fun shapingRuns(runs: List<PdfInline>): List<PdfInline> {
        if (runs.none { it is PdfTextRun && it.style.fontFeatures.active }) return runs
        val result = mutableListOf<PdfInline>()
        var pending: PdfTextRun? = null
        val text = StringBuilder()

        fun flush() {
            pending?.let { result += it.copy(text = text.toString()) }
            pending = null
            text.setLength(0)
        }

        for (run in runs) {
            checkCancellation()
            if (run is PdfTextRun && run.style.fontFeatures.active) {
                val previous = pending
                if (previous != null && (previous.style != run.style || previous.navigation != run.navigation || previous.semantics != run.semantics)) flush()
                if (pending == null) pending = run
                text.append(run.text)
            } else {
                flush()
                result += run
            }
        }
        flush()
        return result
    }

    fun requireSupportedText(text: String, bidi: Boolean = false) {
        text.codePoints().forEach { point ->
            val script = Character.UnicodeScript.of(point)
            val category = Character.getType(point)
            val privateGlyph = category == Character.PRIVATE_USE.toInt() && point in 0xE000..0xF8FF
            if ((script !in simpleScripts && !privateGlyph && !(bidi && script == Character.UnicodeScript.HEBREW && point !in 0xFB1D..0xFB4F)) ||
                Character.isISOControl(point) || category in unshapedForbiddenTypes ||
                point != 32 && Character.isWhitespace(point)) {
                throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "Text requires unsupported shaping or control handling")
            }
        }
    }

    private fun checkAutomaticHeight(height: Float, naturalHeight: Float) {
        if (!height.isFinite()) overflow("Automatic line height is not finite", height, 14_400f)
        if (height < 1f) overflow("Automatic line height is below minimum", 1f, height)
        if (height > 14_400f) overflow("Automatic line height exceeds layout limit", height, 14_400f)
        if (height < naturalHeight) overflow("Automatic line height is below natural content height", naturalHeight, height)
    }

    private fun overflow(reason: String, required: Float, available: Float, unit: String = "pt"): Nothing =
        throw PdfRenderException(PdfFailureCode.CONTENT_OVERFLOW, "$reason; required=$required $unit; available=$available $unit")

    internal fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw PdfRenderException(PdfFailureCode.CANCELLED, "PDF rendering cancelled")
    }

}

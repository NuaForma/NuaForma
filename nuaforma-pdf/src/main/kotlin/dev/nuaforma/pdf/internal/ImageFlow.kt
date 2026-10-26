package dev.nuaforma.pdf.internal

import dev.nuaforma.pdf.*

/** Request-local rectangular exclusions; paragraph text is measured against each actual line region. */
internal object ImageFlow {
    data class Exclusion(val left: Float, val top: Float, val right: Float, val bottom: Float, val wrap: PdfImageWrap)
    private data class Slot(val page: Int, val top: Float, val left: Float, val width: Float)

    fun paragraph(p: PdfParagraph, page: PdfPage, pages: MutableList<MutableList<PositionedElement>>, initialTop: Float,
                  previousAfter: Float, exclusions: MutableMap<Int, MutableList<Exclusion>>, measurer: TextMeasurer, budget: Int): Float {
        if (p.format.keepWithNext || p.format.keepLines || p.listLabel != null || p.format.textDirection == PdfTextDirection.RIGHT_TO_LEFT || p.runs.any { it !is PdfTextRun })
            fail(PdfFailureCode.UNSUPPORTED_TEXT, "Floating image paragraphs with keeps, lists, RTL text or inline objects are not qualified")
        val clean = PdfParagraph(p.runs, 0f, 0f, p.format, p.listLabel)
        val width = page.width - page.margins.left - page.margins.right
        val bottom = page.height - page.margins.bottom
        val base = FlowLayouter.paragraph(clean, width, measurer)
        val height = base.lineHeight
        val before = if (p.format.collapseSpacing) maxOf(0f, p.spaceBefore - previousAfter) else p.spaceBefore
        var start = initialTop + before
        var anchorPage = pages.lastIndex
        val minimumLines = minOf(base.lines.size, if (p.format.widowControl) 2 else 1)
        val required = maxOf(height * minimumLines, p.anchoredImages.filter { it.anchor == PdfBoxAnchor.PARAGRAPH }.maxOfOrNull {
            it.top + it.image.height + it.distances.bottom + if (it.wrap == PdfImageWrap.TOP_BOTTOM) height * minimumLines else 0f
        } ?: 0f)
        if (required > bottom - page.margins.top + .001f) fail(PdfFailureCode.CONTENT_OVERFLOW, "Anchored image and paragraph cannot fit a fresh page")
        if (start + required > bottom + .001f) { anchorPage++; start = page.margins.top }
        fun checkPage(index: Int) { if (index >= budget) fail(PdfFailureCode.RESOURCE_LIMIT, "Page budget exceeded") }
        checkPage(anchorPage)
        val placements = p.anchoredImages.map { anchor ->
            val y = anchor.top + if (anchor.anchor == PdfBoxAnchor.PARAGRAPH) start else 0f
            if (anchor.left + anchor.image.width > page.width + .001f || y + anchor.image.height > page.height + .001f)
                fail(PdfFailureCode.CONTENT_OVERFLOW, "Anchored image exceeds the physical page")
            if (anchor.wrap != PdfImageWrap.NONE && (y - anchor.distances.top < start - .001f || y + anchor.image.height + anchor.distances.bottom > bottom + .001f))
                fail(PdfFailureCode.CONTENT_OVERFLOW, "Wrapped image exceeds its body flow area")
            PositionedImage(anchor.image, anchor.left, y, foreground = true) to Exclusion(anchor.left - anchor.distances.left, y - anchor.distances.top,
                anchor.left + anchor.image.width + anchor.distances.right, y + anchor.image.height + anchor.distances.bottom, anchor.wrap)
        }
        val preceding = pages.getOrNull(anchorPage).orEmpty().filterIsInstance<PositionedImage>().filter { it.foreground }.toMutableList()
        for ((image, _) in placements) {
            if (preceding.any { image.x < it.x + it.image.width - .001f && image.x + image.image.width > it.x + .001f &&
                    image.top < it.top + it.image.height - .001f && image.top + image.image.height > it.top + .001f })
                fail(PdfFailureCode.UNSUPPORTED_TEXT, "Overlapping floating image layers are not qualified")
            preceding += image
        }
        val local = exclusions.mapValues { it.value.toMutableList() }.toMutableMap()
        local.getOrPut(anchorPage) { mutableListOf() }.addAll(placements.map { it.second }.filter { it.wrap != PdfImageWrap.NONE })
        val forced = mutableSetOf<Int>()
        var slots = mutableListOf<Slot>()
        fun slot(index: Int): Slot {
            while (slots.size <= index) {
                PageLayouter.checkCancellation()
                if (slots.size >= 100_001) fail(PdfFailureCode.RESOURCE_LIMIT, "Wrapped line budget exceeded")
                var number = slots.lastOrNull()?.page ?: anchorPage
                var y = slots.lastOrNull()?.let { it.top + height } ?: start
                if (slots.size in forced) { number++; y = page.margins.top }
                while (true) {
                    PageLayouter.checkCancellation()
                    if (y + height > bottom + .001f) { number++; y = page.margins.top }
                    checkPage(number)
                    val active = local[number].orEmpty().filter { y < it.bottom - .001f && y + height > it.top + .001f }
                    var regions = listOf(page.margins.left to page.width - page.margins.right)
                    for (area in active) {
                        regions = regions.flatMap { (left, right) -> when (area.wrap) {
                            PdfImageWrap.TOP_BOTTOM -> emptyList()
                            PdfImageWrap.LEFT -> listOf(left to minOf(right, area.left))
                            PdfImageWrap.RIGHT -> listOf(maxOf(left, area.right) to right)
                            PdfImageWrap.LARGEST -> listOf(if (area.left - left > right - area.right) left to minOf(right, area.left) else maxOf(left, area.right) to right)
                            PdfImageWrap.BOTH -> listOf(left to minOf(right, area.left), maxOf(left, area.right) to right)
                            PdfImageWrap.NONE -> listOf(left to right)
                        } }.filter { (left, right) -> right > left + .001f }
                    }
                    val usable = regions.filter { (left, right) -> minOf(right, page.width - page.margins.right - p.format.rightIndent) > maxOf(left, page.margins.left + p.format.leftIndent + maxOf(0f, p.format.firstLineIndent)) + .001f }
                    if (usable.isNotEmpty()) {
                        slots.addAll(usable.map { (left, right) -> Slot(number, y, left - page.margins.left, right - left) })
                        break
                    }
                    if (active.isEmpty()) fail(PdfFailureCode.CONTENT_OVERFLOW, "No usable wrapped line area")
                    y = active.minOf { it.bottom }
                }
            }
            return slots[index]
        }
        var measured: FlowLayouter.MeasuredParagraph? = null
        for (attempt in 0 until 64) {
            slots = mutableListOf()
            val candidate = FlowLayouter.paragraph(clean, width, measurer) { index -> slot(index).let { it.left to it.width } }
            val used = slots.take(candidate.lines.size)
            val groups = used.withIndex().groupBy { it.value.page }.values.toList()
            val single = if (p.format.widowControl && groups.size > 1) groups.indexOfFirst { group -> group.map { it.value.top }.distinct().size == 1 } else -1
            if (single < 0) { measured = candidate; break }
            val index = if (single == groups.lastIndex) {
                val previous = groups[single - 1]
                previous.first { it.value.top == previous.last().value.top }.index
            } else groups[single].first().index
            if (index == 0 && p.anchoredImages.isNotEmpty()) fail(PdfFailureCode.CONTENT_OVERFLOW, "Image anchor cannot preserve the paragraph widow boundary")
            if (!forced.add(index)) fail(PdfFailureCode.CONTENT_OVERFLOW, "Wrapped paragraph cannot preserve widow control")
        }
        val result = measured ?: fail(PdfFailureCode.CONTENT_OVERFLOW, "Wrapped paragraph layout did not converge")
        val used = slots.take(result.lines.size)
        val finalPage = maxOf(anchorPage, used.lastOrNull()?.page ?: anchorPage)
        while (pages.lastIndex < finalPage) pages += mutableListOf<PositionedElement>()
        pages[anchorPage].addAll(placements.map { it.first })
        exclusions.getOrPut(anchorPage) { mutableListOf() }.addAll(placements.map { it.second }.filter { it.wrap != PdfImageWrap.NONE })
        result.lines.forEachIndexed { index, line ->
            val target = used[index]
            pages[target.page].addAll(line.map { element ->
                when (element) {
                    is PositionedText -> element.copy(x = element.x + page.margins.left, baseline = element.baseline + target.top)
                    else -> fail(PdfFailureCode.UNSUPPORTED_TEXT, "Unexpected object in wrapped text")
                }
            })
        }
        return (used.lastOrNull()?.let { it.top + height } ?: start) + p.spaceAfter
    }

    private fun fail(code: PdfFailureCode, message: String): Nothing = throw PdfRenderException(code, message)
}

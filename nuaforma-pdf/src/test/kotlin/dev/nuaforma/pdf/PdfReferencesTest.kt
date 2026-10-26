package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class PdfReferencesTest {
    private val style = PdfTextStyle("regular", 10f)
    private val page = PdfPage(320f, 360f, PdfMargins(20f, 20f, 20f, 20f))
    private val measure = TextMeasurer { text, _ -> TextMetrics(text.length * 5f, 8f, 2f) }
    private fun p(runs: List<PdfInline>, semantic: PdfBlockSemantics? = null, breakBefore: Boolean = false, level: Int? = null) =
        PdfParagraph(runs, 0f, 4f, PdfParagraphFormat(exactLineHeight = 14f, pageBreakBefore = breakBefore, outlineLevel = level), semantics = semantic)
    private fun plain(text: String) = p(listOf(PdfTextRun(text, style)))
    private fun report(): PdfDocument {
        val seq = PdfSequenceField("seq-figure1", "figures", style, restartAtHeadingLevel = 0,
            navigation = PdfTextNavigation(destinations = listOf(PdfDestination("figure-number"))))
        val figure = PdfImage(FoundationLetter.resource("/fixtures/nuaforma-icon-transparent.png"), 24f, 24f,
            semantics = PdfBlockSemantics("figure1", PdfSemanticRole.FIGURE))
        val contents = PdfParagraph(listOf(PdfTextRun(" ", style)), spaceAfter = 4f,
            semantics = PdfBlockSemantics("toc", PdfSemanticRole.CONTENTS), contents = PdfContents(listOf(
                PdfContentsLevel(0, style, PdfParagraphFormat(exactLineHeight = 14f)),
                PdfContentsLevel(1, style, PdfParagraphFormat(leftIndent = 12f, exactLineHeight = 14f)))))
        val blocks = listOf(plain("Contents"), contents,
            p(listOf(PdfTextRun("First chapter", style)), PdfBlockSemantics("chapter1", PdfSemanticRole.HEADING, 0), level = 0),
            figure,
            p(listOf(PdfTextRun("Figure ", style), seq), PdfBlockSemantics("caption1", PdfSemanticRole.CAPTION, captionTargetId = "figure1")),
            p(listOf(PdfTextRun("Details", style)), PdfBlockSemantics("details", PdfSemanticRole.HEADING, 1), level = 1),
            p(listOf(PdfTextRun("See Figure ", style), PdfReferenceField("ref-number", PdfReferenceKind.TEXT, style, "figure-number"),
                PdfTextRun(" on page ", style), PdfReferenceField("ref-page", PdfReferenceKind.PAGE, style, "caption1"))),
            p(listOf(PdfTextRun("Current ", style), PdfReferenceField("current", PdfReferenceKind.CURRENT_PAGE, style),
                PdfTextRun(" total ", style), PdfReferenceField("total", PdfReferenceKind.TOTAL_PAGES, style),
                PdfTextRun(" sequence ", style), PdfReferenceField("sequence", PdfReferenceKind.SEQUENCE_PAGES, style, "sequence0"))),
            p(listOf(PdfTextRun("Second chapter", style)), PdfBlockSemantics("chapter2", PdfSemanticRole.HEADING, 0), true, 0),
            p(listOf(PdfTextRun("Figure ", style), PdfSequenceField("seq-figure2", "figures", style, restartAtHeadingLevel = 0)), PdfBlockSemantics("caption2", PdfSemanticRole.CAPTION)),
            p(listOf(PdfTextRun("Previous figure ", style), PdfReferenceField("back", PdfReferenceKind.TEXT, style, "figure-number"))))
        return PdfDocument(listOf(PdfPageSequence(page, blocks, PdfFlow(), PdfPageNumbering(5))), 8,
            listOf(PdfReferenceTarget("figure-number", listOf(seq))))
    }

    @Test fun `contents captions and cross references converge with correct page numbering and stable logical ownership`() {
        val document = report()
        val result = ReferenceLayouter.layout(document, measure, PdfRenderControl())
        assertEquals(2, result.pages.size)
        assertEquals(listOf(5, 6), result.displayNumbers)
        for ((id, value) in mapOf("seq-figure1" to "1", "seq-figure2" to "1", "ref-number" to "1", "ref-page" to "5", "current" to "5", "total" to "2", "sequence" to "2", "back" to "1"))
            assertEquals(value, result.fieldValues[id], id)
        assertTrue(result.identities.size >= 2)
        assertEquals(1, result.identities.distinct().size)
        val nodes = requireNotNull(result.structure).nodes.associateBy { it.id }
        assertEquals(PdfSemanticRole.CONTENTS, nodes.getValue("toc").role)
        assertEquals("figure1", nodes.getValue("caption1").targetId)
        assertEquals("seq-figure1", nodes.getValue("ref-number").targetId)
        assertEquals("chapter1-section", nodes.getValue("details-section").parentId)
        val entries = nodes.values.filter { it.role == PdfSemanticRole.CONTENTS_ENTRY }
        assertEquals(listOf(0, 1, 0), entries.map { it.level })
        assertTrue(entries.all { it.parentId == "toc" })
        for (physical in result.pages) for (text in DocumentLayouter.texts(physical.elements)) {
            assertNotNull(text.logicalOwner)
            assertTrue(text.logicalOwner in nodes)
            if (text.span != null) assertTrue(text.span.id in nodes)
        }
        assertTrue(document.blocks.filterIsInstance<PdfParagraph>().any { it.contents != null })
        assertTrue(document.blocks.filterIsInstance<PdfParagraph>().flatMap { it.runs }.any { it is PdfSequenceField })
    }

    @Test fun `real PDF contains working contents links and hierarchical outlines without a level A claim`() {
        Loader.loadPDF(PdfEngine().render(report(), FoundationLetter.fonts())).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("See Figure 1 on page 5"))
            assertTrue(text.contains("Current 5 total 2 sequence 2"))
            val chapter = pdf.documentCatalog.documentOutline.firstChild
            assertEquals("First chapter", chapter.title)
            assertEquals("Details", chapter.firstChild.title)
            assertEquals("Second chapter", chapter.nextSibling.title)
            assertEquals(1, pdf.documentCatalog.names.dests.names.getValue("chapter2").retrievePageNumber())
            assertTrue(pdf.getPage(0).annotations.map { it as PDAnnotationLink }.any { (it.destination as? PDNamedDestination)?.namedDestination == "chapter2" })
            assertNull(pdf.documentCatalog.structureTreeRoot)
            assertFalse(pdf.documentCatalog.markInfo?.isMarked ?: false)
        }
    }

    @Test fun `decimal sequences support explicit restarts repeats and continued increments`() {
        val fields = listOf(PdfSequenceField("a", "example", style, restart = 4), PdfSequenceField("b", "example", style, repeat = true),
            PdfSequenceField("c", "example", style), PdfSequenceField("d", "example", style, restart = 0), PdfSequenceField("e", "example", style))
        val doc = PdfDocument(page, fields.map { p(listOf(it)) }, PdfFlow())
        assertEquals(listOf("4", "4", "5", "0", "1"), ReferenceLayouter.layout(doc, measure, PdfRenderControl()).fieldValues.values.toList())
    }

    @Test fun `current and total counts respect section restarts and sequence scope`() {
        val next = PdfPage(420f, 300f, page.margins)
        val doc = PdfDocument(listOf(PdfPageSequence(page, listOf(plain("First")), PdfFlow()),
            PdfPageSequence(next, listOf(p(listOf(PdfReferenceField("current", PdfReferenceKind.CURRENT_PAGE, style),
                PdfTextRun(" of ", style), PdfReferenceField("count", PdfReferenceKind.TOTAL_PAGES, style),
                PdfTextRun(" section ", style), PdfReferenceField("local", PdfReferenceKind.SEQUENCE_PAGES, style, "sequence1")))), PdfFlow(), PdfPageNumbering(10))))
        val result = ReferenceLayouter.layout(doc, measure, PdfRenderControl())
        assertEquals(listOf(1, 10), result.displayNumbers)
        assertEquals(mapOf("current" to "10", "count" to "2", "local" to "1"), result.fieldValues)
    }

    @Test fun `missing ranges cycles and expanded text cannot silently use a cache or escape budgets`() {
        val recursive = PdfReferenceField("recursive", PdfReferenceKind.TEXT, style, "range", linked = false)
        val cycle = PdfDocument(page, listOf(p(listOf(recursive))), PdfFlow(), listOf(PdfReferenceTarget("range", listOf(recursive))))
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) { ReferenceLayouter.layout(cycle, measure, PdfRenderControl()) }.code)
        val missing = PdfDocument(page, listOf(p(listOf(PdfReferenceField("missing", PdfReferenceKind.PAGE, style, "nowhere", linked = false)))), PdfFlow())
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) { ReferenceLayouter.layout(missing, measure, PdfRenderControl()) }.code)
        val huge = PdfDocument(page, listOf(p((1..3).map { PdfReferenceField("ref$it", PdfReferenceKind.TEXT, style, "large", linked = false) })), PdfFlow(),
            listOf(PdfReferenceTarget("large", listOf(PdfTextRun("a ".repeat(20_000), style)))))
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, assertThrows(PdfRenderException::class.java) { ReferenceLayouter.layout(huge, measure, PdfRenderControl()) }.code)
    }

    @Test fun `unpainted navigation targets and incorrect logical associations reject explicitly`() {
        val hidden = p(listOf(PdfTextRun("Hidden", style, PdfTextNavigation(destinations = listOf(PdfDestination("hidden"))))))
        val flow = PdfFlow(evenPageBands = PdfPageBands(PdfPageBand(0f, 18f, listOf(hidden))))
        val doc = PdfDocument(page, listOf(p(listOf(PdfTextRun("Link", style, PdfTextNavigation(PdfLink.Internal("hidden")))))), flow)
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) { PdfEngine().render(doc, FoundationLetter.fonts()) }.code)
        val bad = PdfDocument(page, listOf(p(listOf(PdfTextRun("Caption", style)), PdfBlockSemantics("caption", PdfSemanticRole.CAPTION, captionTargetId = "absent"))), PdfFlow())
        assertThrows(IllegalArgumentException::class.java) { ReferenceLayouter.layout(bad, measure, PdfRenderControl()) }
    }

    @Test fun `shared engine requests keep sequence counters and model snapshots independent`() {
        val engine = PdfEngine()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = pool.invokeAll((1..8).map { number -> Callable {
                val field = PdfSequenceField("counter", "shared-name", style, restart = number)
                val model = PdfDocument(page, listOf(p(listOf(PdfTextRun("Figure ", style), field))), PdfFlow())
                Loader.loadPDF(engine.render(model, FoundationLetter.fonts())).use { pdf ->
                    assertTrue(PDFTextStripper().getText(pdf).contains("Figure $number"))
                }
                assertSame(field, (model.blocks.single() as PdfParagraph).runs.last())
                number
            } })
            assertEquals((1..8).toList(), jobs.map { it.get() })
        } finally { pool.shutdownNow() }
    }

    @Test fun `logical text offsets survive wrapping and page fragmentation while long identities remain stable`() {
        val headingId = "h".repeat(128)
        val text = "alpha beta gamma ".repeat(42).trimEnd()
        val small = PdfPage(320f, 180f, page.margins)
        val contentsLevels = mutableListOf(PdfContentsLevel(0, style, PdfParagraphFormat(exactLineHeight = 14f)))
        val contents = PdfContents(contentsLevels)
        contentsLevels.clear()
        assertEquals(1, contents.levels.size)
        val document = PdfDocument(small, listOf(
            PdfParagraph(listOf(PdfTextRun(" ", style)), semantics = PdfBlockSemantics("contents", PdfSemanticRole.CONTENTS), contents = contents),
            p(listOf(PdfTextRun("Heading", style)), PdfBlockSemantics(headingId, PdfSemanticRole.HEADING, 0), level = 0),
            p(listOf(PdfTextRun(text, style)), PdfBlockSemantics("wrapped-body"))), PdfFlow())
        val result = ReferenceLayouter.layout(document, measure, PdfRenderControl())
        assertTrue(result.pages.size > 1)
        val nodes = requireNotNull(result.structure).nodes
        assertEquals(1, result.identities.distinct().size)
        assertTrue(nodes.all { it.id.length <= 128 })
        val fragments = result.pages.flatMap { DocumentLayouter.texts(it.elements).toList() }.filter { it.logicalOwner == "wrapped-body" }
        assertEquals(text.split(' '), fragments.filter { it.text.isNotBlank() }.map { it.text })
        assertTrue(fragments.zipWithNext().all { (first, second) -> requireNotNull(first.logicalOffset) < requireNotNull(second.logicalOffset) })
        for (fragment in fragments) {
            val offset = requireNotNull(fragment.logicalOffset)
            assertEquals(fragment.text, text.substring(offset, offset + fragment.text.length))
        }
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) { (nodes as MutableList<PdfSemanticNode>).clear() }
    }

    @Test fun `table headers frames labels and whole caption references retain their logical relationships`() {
        val number = PdfSequenceField("number", "figures", style, navigation = PdfTextNavigation(destinations = listOf(PdfDestination("caption-range"))))
        val suffix = PdfTextRun(" - a figure", style)
        val table = PdfTable(listOf(200f), listOf(
            PdfTableRow(listOf(PdfTableCell(listOf(plain("Header"))))),
            PdfTableRow(listOf(PdfTableCell(listOf(plain("Data")))))), repeatedHeaderRows = 1,
            semantics = PdfBlockSemantics("table", PdfSemanticRole.TABLE))
        val frame = PdfTextBox(30f, 250f, 180f, 30f, listOf(plain("Frame")), PdfBoxAnchor.PAGE, semantics = PdfBlockSemantics("frame", PdfSemanticRole.FRAME))
        val list = PdfParagraph(listOf(PdfTextRun("List item", style)), format = PdfParagraphFormat(leftIndent = 24f, firstLineIndent = -24f, exactLineHeight = 14f),
            listLabel = PdfListLabel(PdfListValue("list", 0, 1, "1."), style, tabStop = 24f), semantics = PdfBlockSemantics("item"))
        val doc = PdfDocument(page, listOf(table, list, frame,
            p(listOf(number, suffix), PdfBlockSemantics("caption", PdfSemanticRole.CAPTION)),
            p(listOf(PdfReferenceField("whole-caption", PdfReferenceKind.TEXT, style, "caption-range")))), PdfFlow(),
            listOf(PdfReferenceTarget("caption-range", listOf(number, suffix))))
        val result = ReferenceLayouter.layout(doc, measure, PdfRenderControl())
        val nodes = requireNotNull(result.structure).nodes
        assertEquals(1, nodes.count { it.role == PdfSemanticRole.TABLE_HEADER_CELL })
        assertEquals(1, nodes.count { it.role == PdfSemanticRole.TABLE_CELL })
        assertEquals("caption", nodes.single { it.id == "whole-caption" }.targetId)
        assertEquals("frame", nodes.single { it.text == "Frame" }.parentId)
        assertEquals("item", nodes.single { it.id == "item-label" }.parentId)
        val paragraphText = nodes.filter { it.role in setOf(PdfSemanticRole.PARAGRAPH, PdfSemanticRole.CAPTION) }.associate { it.id to it.text }
        for (physical in result.pages) for (text in DocumentLayouter.texts(physical.elements)) {
            val original = requireNotNull(paragraphText[text.logicalOwner])
            val offset = requireNotNull(text.logicalOffset)
            assertEquals(text.text, original.substring(offset, offset + text.text.length))
        }
    }

    @Test fun `two digit references converge and repeated header current page fields reject`() {
        val blocks = listOf(p(listOf(PdfTextRun("Total ", style), PdfReferenceField("total", PdfReferenceKind.TOTAL_PAGES, style),
            PdfTextRun(" final ", style), PdfReferenceField("last", PdfReferenceKind.PAGE, style, "chapter9"))),
            PdfParagraph(listOf(PdfTextRun(" ", style)), semantics = PdfBlockSemantics("contents", PdfSemanticRole.CONTENTS),
                contents = PdfContents(listOf(PdfContentsLevel(0, style, PdfParagraphFormat(exactLineHeight = 14f)))))) +
            (1..9).map { p(listOf(PdfTextRun("Chapter $it", style)), PdfBlockSemantics("chapter$it", PdfSemanticRole.HEADING, 0), true, 0) }
        val result = ReferenceLayouter.layout(PdfDocument(page, blocks, PdfFlow()), measure, PdfRenderControl())
        assertEquals(10, result.pages.size)
        assertEquals("10", result.fieldValues["total"]); assertEquals("10", result.fieldValues["last"])
        assertTrue(result.identities.size >= 2); assertEquals(1, result.identities.distinct().size)
        val header = PdfTableRow(listOf(PdfTableCell(listOf(p(listOf(PdfReferenceField("current", PdfReferenceKind.CURRENT_PAGE, style)))))))
        val data = (1..6).map { PdfTableRow(listOf(PdfTableCell(listOf(plain("Row $it"))))) }
        val table = PdfTable(listOf(120f), listOf(header) + data, repeatedHeaderRows = 1)
        val repeated = PdfDocument(PdfPage(180f, 140f, page.margins), listOf(table), PdfFlow())
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            ReferenceLayouter.layout(repeated, measure, PdfRenderControl())
        }.code)
    }
}

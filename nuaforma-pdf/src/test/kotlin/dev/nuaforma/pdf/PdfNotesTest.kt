package dev.nuaforma.pdf

import dev.nuaforma.pdf.internal.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class PdfNotesTest {
    private val style = PdfTextStyle("regular", 10f)
    private val marker = style.copy(size = 6f, baselineShift = 3f)
    private val noteStyle = style.copy(size = 9f)
    private val page = PdfPage(320f, 360f, PdfMargins(20f, 20f, 20f, 20f))
    private fun p(runs: List<PdfInline>, height: Float = 14f, after: Float = 4f) = PdfParagraph(runs, spaceAfter = after,
        format = PdfParagraphFormat(exactLineHeight = height))
    private fun text(value: String) = p(listOf(PdfTextRun(value, style)))
    private fun note(id: String, kind: PdfNoteKind, number: Int = 1, extra: Int = 0): PdfNote {
        val paragraphs = listOf(p(listOf(PdfNoteLabel(marker), PdfTextRun(" Note $id", noteStyle)), 12f, 0f)) +
            (1..extra).map { p(listOf(PdfTextRun("$id-line-$it", noteStyle)), 12f, 0f) }
        return PdfNote(id, kind, PdfListValue("notes", 0, number, number.toString()), paragraphs, PdfNoteSeparator(width = 100f))
    }
    private val measure = TextMeasurer { value, style -> TextMetrics(value.length * style.size / 2f,
        style.size * .8f + style.baselineShift, style.size * .2f - style.baselineShift) }

    @Test fun `footnotes and endnotes retain stable structure correct references raised glyphs and real backlinks`() {
        val foot = note("foot", PdfNoteKind.FOOTNOTE)
        val end = note("end", PdfNoteKind.ENDNOTE)
        val body = p(listOf(PdfTextRun("Body", style), PdfNoteCall("foot-call", foot.id, marker),
            PdfTextRun(" and end", style), PdfNoteCall("end-call", end.id, marker)))
        val repeated = p(listOf(PdfTextRun("Again ", style), PdfReferenceField("cite", PdfReferenceKind.TEXT, style, foot.labelTarget),
            PdfTextRun(" total ", style), PdfReferenceField("total", PdfReferenceKind.TOTAL_PAGES, style)))
        val document = PdfDocument(page, listOf(body, repeated, text("Last body line")), PdfFlow(), notes = listOf(foot, end))
        val composed = ReferenceLayouter.layout(document, measure, PdfRenderControl())
        assertEquals(1, composed.pages.size)
        assertEquals("1", composed.fieldValues["cite"])
        val nodes = requireNotNull(composed.structure).nodes.associateBy { it.id }
        assertEquals(PdfSemanticRole.NOTE, nodes.getValue("foot").role)
        assertEquals("foot", nodes.getValue("foot-call").targetId)
        assertEquals(PdfSemanticRole.NOTE_REFERENCE, nodes.getValue("foot-call").role)
        assertEquals(1, composed.identities.distinct().size)
        val placed = DocumentLayouter.texts(composed.pages.single().elements).toList()
        val bodyEnd = placed.single { it.text == "Last" }.baseline
        val footStart = placed.filter { it.logicalOwner?.startsWith("note0-") == true }.minOf { it.baseline }
        val endStart = placed.filter { it.logicalOwner?.startsWith("note1-") == true }.minOf { it.baseline }
        assertTrue(bodyEnd < endStart && endStart < footStart)
        Loader.loadPDF(PdfEngine().render(document, FoundationLetter.fonts())).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Note foot")); assertTrue(text.contains("Note end")); assertTrue(text.contains("Again 1 total 1"))
            assertTrue(pdf.documentCatalog.names.dests.names.keys.containsAll(listOf("foot", "end", "foot-call", "end-call", foot.labelTarget)))
            assertTrue(pdf.getPage(0).annotations.size >= 5)
        }
        assertTrue(body.runs.any { it is PdfNoteCall })
        assertTrue(foot.paragraphs.first().runs.first() is PdfNoteLabel)
    }

    @Test fun `long footnotes continue without duplicated labels dropped lines or extra blank pages`() {
        val foot = note("long", PdfNoteKind.FOOTNOTE, extra = 34)
        val small = PdfPage(320f, 180f, page.margins)
        val document = PdfDocument(small, listOf(p(listOf(PdfTextRun("Owner", style), PdfNoteCall("call", foot.id, marker))), text("END")), PdfFlow(), notes = listOf(foot))
        val composed = ReferenceLayouter.layout(document, measure, PdfRenderControl())
        assertTrue(composed.pages.size >= 4)
        val painted = composed.pages.flatMap { DocumentLayouter.texts(it.elements).toList() }
        assertEquals(1, painted.count { it.span?.id == foot.labelTarget })
        assertEquals(34, painted.count { it.text.startsWith("long-line-") })
        assertTrue(composed.pages.all { DocumentLayouter.texts(it.elements).any() })
        assertTrue(composed.pages.drop(1).any { page -> page.elements.filterIsInstance<PositionedRectangle>().any { it.width == 280f } })
        val first = composed.pages.first().elements.filterIsInstance<PositionedRectangle>().single()
        assertEquals(100f, first.width)
        Loader.loadPDF(PdfEngine().render(document, FoundationLetter.fonts())).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            for (index in 1..34) assertEquals(1, Regex("long-line-$index(?![0-9])").findAll(text).count())
            assertTrue(text.contains("END"))
        }
    }

    @Test fun `orphan nested duplicate missing and misplaced owning calls reject explicitly`() {
        val foot = note("one", PdfNoteKind.FOOTNOTE)
        val call = PdfNoteCall("call", foot.id, marker)
        val cases = listOf(
            PdfDocument(page, listOf(p(listOf(call))), PdfFlow()),
            PdfDocument(page, listOf(text("No call")), PdfFlow(), notes = listOf(foot)),
            PdfDocument(page, listOf(p(listOf(call, call.copy(id = "again")))), PdfFlow(), notes = listOf(foot)),
            PdfDocument(page, listOf(p(listOf(PdfNoteLabel(marker)))), PdfFlow()),
            PdfDocument(page, listOf(p(listOf(call))), notes = listOf(foot)),
            PdfDocument(page, listOf(text("Body")), PdfFlow(header = PdfPageBand(0f, 16f, listOf(p(listOf(call))))), notes = listOf(foot)),
        )
        for (document in cases) assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            ReferenceLayouter.layout(document, measure, PdfRenderControl())
        }.code)
        assertThrows(IllegalArgumentException::class.java) {
            PdfNote("nested", PdfNoteKind.FOOTNOTE, foot.number, listOf(p(listOf(PdfNoteLabel(marker), call))))
        }
    }

    @Test fun `concurrent note plans and source numbers stay private to each render`() {
        val engine = PdfEngine()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..8).map { number -> Callable {
                val foot = note("private", PdfNoteKind.FOOTNOTE, number)
                val document = PdfDocument(page, listOf(p(listOf(PdfTextRun("Owner", style), PdfNoteCall("call", foot.id, marker)))), PdfFlow(), notes = listOf(foot))
                Loader.loadPDF(engine.render(document, FoundationLetter.fonts())).use { pdf ->
                    assertTrue(PDFTextStripper().getText(pdf).contains("Note private"))
                    assertEquals(0, pdf.documentCatalog.names.dests.names.getValue("private").retrievePageNumber())
                }
                assertEquals(number.toString(), foot.number.text)
                number
            } })
            assertEquals((1..8).toList(), results.map { it.get() })
        } finally { pool.shutdownNow() }
    }

    @Test fun `atomic table rows repeat headers and retain notes on the owning row page`() {
        val foot = note("table-note", PdfNoteKind.FOOTNOTE, extra = 2)
        val header = PdfTableRow(listOf(PdfTableCell(listOf(text("Header")))))
        val rows = (1..10).map { index -> PdfTableRow(listOf(PdfTableCell(listOf(
            if (index == 5) p(listOf(PdfTextRun("Owner row 5", style), PdfNoteCall("row-call", foot.id, marker))) else text("Row $index")))), splitOversized = true) }
        val table = PdfTable(listOf(280f), listOf(header) + rows, repeatedHeaderRows = 1)
        val document = PdfDocument(PdfPage(320f, 220f, page.margins), listOf(table, text("END")), PdfFlow(), notes = listOf(foot))
        val layout = ReferenceLayouter.layout(document, measure, PdfRenderControl())
        assertTrue(layout.pages.size >= 2)
        val owning = layout.pages.indexOfFirst { DocumentLayouter.texts(it.elements).any { text -> text.span?.id == "row-call" } }
        assertTrue(DocumentLayouter.texts(layout.pages[owning].elements).any { it.navigation?.destinations?.any { d -> d.name == foot.id } == true })
        assertTrue(layout.pages.take(layout.pages.size - 1).all { DocumentLayouter.texts(it.elements).any { text -> text.text == "Header" } })
        val texts = layout.pages.flatMap { DocumentLayouter.texts(it.elements).toList() }
        assertEquals(1, texts.count { it.span?.id == "row-call" })
        assertTrue(texts.any { it.text == "END" })
    }

    @Test fun `paragraph keeps move their note with the heading and long endnotes finish after the body`() {
        val foot = note("kept", PdfNoteKind.FOOTNOTE)
        val end = note("long-end", PdfNoteKind.ENDNOTE, extra = 31)
        val small = PdfPage(320f, 180f, page.margins)
        val heading = PdfParagraph(listOf(PdfTextRun("Heading", style)), format = PdfParagraphFormat(exactLineHeight = 14f, keepWithNext = true))
        val owner = p(listOf(PdfTextRun("Owner", style), PdfNoteCall("kept-call", foot.id, marker), PdfNoteCall("end-call", end.id, marker)))
        val document = PdfDocument(small, (1..6).map { text("Filler $it") } + listOf(heading, owner, text("END")), PdfFlow(), notes = listOf(foot, end))
        val layout = ReferenceLayouter.layout(document, measure, PdfRenderControl())
        val pages = layout.pages.map { DocumentLayouter.texts(it.elements).toList() }
        val ownerPage = pages.indexOfFirst { list -> list.any { it.span?.id == "kept-call" } }
        assertTrue(ownerPage > 0)
        assertTrue(pages[ownerPage].any { it.text == "Heading" })
        assertTrue(pages[ownerPage].any { it.navigation?.destinations?.any { d -> d.name == foot.id } == true })
        val endPage = pages.indexOfFirst { list -> list.any { it.text == "END" } }
        val firstEndnotePage = pages.indexOfFirst { list -> list.any { it.navigation?.destinations?.any { d -> d.name == end.id } == true } }
        assertTrue(firstEndnotePage >= endPage)
        assertEquals(31, pages.flatten().count { it.text.startsWith("long-end-line-") })
        assertTrue(pages.all { it.isNotEmpty() })
    }

    @Test fun `impossible protected fragments page budgets and unsupported table combinations fail boundedly`() {
        val foot = note("protected", PdfNoteKind.FOOTNOTE, extra = 4)
        val owner = p(listOf(PdfTextRun("Owner", style), PdfNoteCall("call", foot.id, marker)))
        val protected = PdfNote(foot.id, foot.kind, foot.number, foot.paragraphs.map { paragraph ->
            PdfParagraph(paragraph.runs, format = paragraph.format.copy(keepWithNext = true))
        }, foot.separator)
        val short = PdfPage(320f, 80f, page.margins)
        assertEquals(PdfFailureCode.CONTENT_OVERFLOW, assertThrows(PdfRenderException::class.java) {
            ReferenceLayouter.layout(PdfDocument(short, listOf(owner), PdfFlow(), notes = listOf(protected)), measure, PdfRenderControl())
        }.code)
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, assertThrows(PdfRenderException::class.java) {
            ReferenceLayouter.layout(PdfDocument(PdfPage(320f, 100f, page.margins), listOf(owner), PdfFlow(maxPages = 1), notes = listOf(foot)), measure, PdfRenderControl())
        }.code)
        for (table in listOf(
            PdfTable(listOf(280f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(owner))), allowSplit = true))),
            PdfTable(listOf(280f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(owner)))), PdfTableRow(listOf(PdfTableCell(listOf(text("Data")))))), repeatedHeaderRows = 1),
        )) assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            ReferenceLayouter.layout(PdfDocument(page, listOf(table), PdfFlow(), notes = listOf(foot)), measure, PdfRenderControl())
        }.code)
        val cancelled = PdfRenderControl(checkpoint = Runnable { throw PdfRenderException(PdfFailureCode.CANCELLED, "Test cancellation") })
        assertEquals(PdfFailureCode.CANCELLED, assertThrows(PdfRenderException::class.java) {
            ReferenceLayouter.layout(PdfDocument(page, listOf(owner), PdfFlow(), notes = listOf(foot)), measure, cancelled)
        }.code)
    }

    @Test fun `ordinary images margin frames and whole nested tables coexist with notes without losing content`() {
        val foot = note("objects", PdfNoteKind.FOOTNOTE)
        val owner = p(listOf(PdfTextRun("Owner", style), PdfNoteCall("call", foot.id, marker)))
        val frame = PdfTextBox(3f, 50f, 14f, 18f, listOf(p(listOf(PdfTextRun("X", style)), after = 0f)),
            PdfBoxAnchor.PAGE, padding = PdfMargins(0f, 0f, 0f, 0f))
        val image = PdfImage(FoundationLetter.resource("/fixtures/nuaforma-icon-transparent.png"), 20f, 20f)
        val inner = PdfTable(listOf(240f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(text("Nested")))))))
        val outer = PdfTable(listOf(280f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(inner))))))
        val kept = PdfParagraph(listOf(PdfTextRun("Table heading", style)), format = PdfParagraphFormat(exactLineHeight = 14f, keepWithNext = true))
        val atomic = PdfTable(listOf(280f), listOf(PdfTableRow(listOf(PdfTableCell(listOf(text("Atomic")))))))
        val document = PdfDocument(page, listOf(owner, frame, image, outer, kept, atomic, text("END")), PdfFlow(), notes = listOf(foot))
        val layout = ReferenceLayouter.layout(document, measure, PdfRenderControl())
        val elements = layout.pages.flatMap { it.elements }
        val text = layout.pages.flatMap { DocumentLayouter.texts(it.elements).toList() }.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ")
        assertTrue(listOf("Nested", "Atomic", "END", "Note objects", "X").all { text.contains(it) }, text)
        assertEquals(1, elements.count { it is PositionedImage })
        assertEquals(1, elements.count { it is PositionedBox })
        for (bad in listOf(
            PdfTextBox(30f, 0f, 100f, 30f, listOf(text("Overlap"))),
            PdfTextBox(30f, 0f, 100f, 30f, listOf(text("Wrapped")), wrap = PdfBoxWrap.TOP_BOTTOM),
        )) assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            ReferenceLayouter.layout(PdfDocument(page, listOf(owner, bad), PdfFlow(), notes = listOf(foot)), measure, PdfRenderControl())
        }.code)
    }

    @Test fun `note paragraph keeps and widows prevent isolated continuation lines`() {
        val owner = p(listOf(PdfTextRun("Owner", style), PdfNoteCall("call", "paragraph-note", marker)))
        val first = p(listOf(PdfNoteLabel(marker), PdfTextRun(" Label", noteStyle)), 12f, 0f)
        val words = (1..24).joinToString(" ") { "token${it.toString().padStart(2, '0')}" }
        for (keep in listOf(false, true)) {
            val paragraph = PdfParagraph(listOf(PdfTextRun(words, noteStyle)), spaceAfter = 0f,
                format = PdfParagraphFormat(exactLineHeight = 12f, keepLines = keep, widowControl = !keep))
            val foot = PdfNote("paragraph-note", PdfNoteKind.FOOTNOTE, PdfListValue("notes", 0, 1, "1"), listOf(first, paragraph), PdfNoteSeparator(width = 100f))
            val document = PdfDocument(PdfPage(320f, 120f, page.margins), listOf(owner, text("END")), PdfFlow(), notes = listOf(foot))
            val layout = ReferenceLayouter.layout(document, measure, PdfRenderControl())
            val fragments = layout.pages.map { page -> DocumentLayouter.texts(page.elements).filter { it.logicalOwner == "note0-b1" }.toList() }.filter { it.isNotEmpty() }
            assertTrue(fragments.all { it.map { text -> text.baseline }.distinct().size >= 2 })
            if (keep) assertEquals(1, fragments.size)
            val text = fragments.flatten().joinToString(" ") { it.text }
            for (index in 1..24) assertEquals(1, Regex("token${index.toString().padStart(2, '0')}(?![0-9])").findAll(text).count())
        }
        val foot = note("paragraph-note", PdfNoteKind.FOOTNOTE)
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            ReferenceLayouter.layout(PdfDocument(PdfPage(130f, 160f, page.margins), listOf(owner), PdfFlow(), notes = listOf(foot)), measure, PdfRenderControl())
        }.code)
    }
}

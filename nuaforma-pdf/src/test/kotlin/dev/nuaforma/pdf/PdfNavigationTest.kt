package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Callable

class PdfNavigationTest {
    @TempDir lateinit var temporary: Path
    private val style = PdfTextStyle("regular", 11f, PdfColor(0, 80, 160))
    private val page = PdfPage(300f, 220f, PdfMargins(30f, 30f, 30f, 30f))
    private fun paragraph(text: String, navigation: PdfTextNavigation? = null, next: Boolean = false) =
        PdfParagraph(listOf(PdfTextRun(text, style, navigation)), 0f, 6f, PdfParagraphFormat(exactLineHeight = 16f, pageBreakBefore = next))
    private fun document(navigation: Boolean = true) = PdfDocument(page, listOf(
        paragraph("Open the project website and read its documentation ".repeat(5), if (navigation) PdfTextNavigation(PdfLink.External("https://nuaforma.dev/image-comparison/")) else null),
        paragraph("Continue to the conclusion", if (navigation) PdfTextNavigation(PdfLink.Internal("Conclusion")) else null),
        paragraph("Conclusion on the following page", if (navigation) PdfTextNavigation(destinations = listOf(PdfDestination("Conclusion", "Conclusion"))) else null, true),
        paragraph("Return to the conclusion", if (navigation) PdfTextNavigation(PdfLink.Internal("Conclusion")) else null)), PdfFlow())

    @Test fun `links follow final wrapped text rectangles and named targets resolve across pages`() {
        Loader.loadPDF(PdfEngine().render(document(), FoundationLetter.fonts())).use { pdf ->
            assertTrue(pdf.numberOfPages >= 2)
            val destination = pdf.documentCatalog.names.dests.names.getValue("Conclusion")
            assertEquals(pdf.numberOfPages - 1, destination.retrievePageNumber())
            assertEquals("Conclusion", pdf.documentCatalog.documentOutline.firstChild.title)
            var external = 0
            var internal = 0
            for ((pageIndex, physical) in pdf.pages.withIndex()) {
                val glyphs = mutableListOf<org.apache.pdfbox.text.TextPosition>()
                object : PDFTextStripper() {
                    init { startPage = pageIndex + 1; endPage = pageIndex + 1 }
                    override fun processTextPosition(text: org.apache.pdfbox.text.TextPosition) {
                        glyphs += text
                        super.processTextPosition(text)
                    }
                }.getText(pdf)
                for (annotation in physical.annotations) {
                    val link = annotation as PDAnnotationLink
                    assertTrue(link.isPrinted)
                    assertEquals(0f, link.border.toFloatArray()[2])
                    assertTrue(link.rectangle.lowerLeftX >= 30f)
                    assertTrue(link.rectangle.upperRightX <= 270.01f)
                    assertTrue(link.rectangle.lowerLeftY >= 0f && link.rectangle.upperRightY <= 220f)
                    if (link.contents.isNotBlank()) {
                        val covered = glyphs.filter { link.rectangle.contains(it.xDirAdj + it.widthDirAdj / 2,
                            physical.mediaBox.height - it.yDirAdj + it.heightDir / 2) }.joinToString("") { it.unicode }.trim()
                        assertEquals(link.contents.trim(), covered, "Clickable rectangle must cover its own visible text")
                    }
                    if (link.action != null) {
                        assertEquals("https://nuaforma.dev/image-comparison/", (link.action as PDActionURI).uri)
                        external++
                    } else {
                        assertEquals("Conclusion", (link.destination as PDNamedDestination).namedDestination)
                        internal++
                    }
                }
            }
            assertTrue(external > 10 && internal >= 2)
            assertEquals(1, Regex("Conclusion on the following page").findAll(PDFTextStripper().getText(pdf)).count())
        }
    }

    @Test fun `navigation changes neither pixels nor extracted text nor pagination`() {
        val engine = PdfEngine()
        Loader.loadPDF(engine.render(document(), FoundationLetter.fonts())).use { with ->
            Loader.loadPDF(engine.render(document(false), FoundationLetter.fonts())).use { without ->
                assertEquals(without.numberOfPages, with.numberOfPages)
                assertEquals(PDFTextStripper().getText(without), PDFTextStripper().getText(with))
                for (i in 0 until with.numberOfPages) {
                    val a = PDFRenderer(with).renderImageWithDPI(i, 96f)
                    val b = PDFRenderer(without).renderImageWithDPI(i, 96f)
                    PdfGoldenImages.assertMatches(b, a, temporary.resolve("navigation-page-${i + 1}"), "Navigation must preserve visible layout")
                }
            }
        }
    }

    @Test fun `unsafe URI schemes credentials control characters and invalid destinations reject`() {
        for (uri in listOf("file:///etc/passwd", "javascript:alert(1)", "data:text/plain,test", "https://user:pass@example.test/", "https://example.test/\n", "https:///missing-host", "https://[", "relative/path"))
            assertThrows(IllegalArgumentException::class.java) { PdfLink.External(uri) }
        for (name in listOf("", "space name", "../outside", "x".repeat(129)))
            assertThrows(IllegalArgumentException::class.java) { PdfDestination(name) }
        for (uri in listOf("https://example.test/a?q=x#part", "http://example.test/", "mailto:person@example.test")) PdfLink.External(uri)
        assertThrows(IllegalArgumentException::class.java) { PdfTextNavigation() }
        assertThrows(IllegalArgumentException::class.java) { PdfDestination("name", "bad\nlabel") }
        assertThrows(IllegalArgumentException::class.java) { PdfTextRun(" ", style, PdfTextNavigation(destinations = listOf(PdfDestination("blank")))) }
    }

    @Test fun `single underlining adds visible ink without moving or changing text`() {
        fun model(underline: Boolean) = PdfDocument(page, listOf(PdfParagraph(listOf(
            PdfTextRun("Underlined link text", style.copy(underline = underline), PdfTextNavigation(PdfLink.External("https://example.test/")))))))
        val engine = PdfEngine()
        Loader.loadPDF(engine.render(model(true), FoundationLetter.fonts())).use { underlined ->
            Loader.loadPDF(engine.render(model(false), FoundationLetter.fonts())).use { plain ->
                assertEquals(PDFTextStripper().getText(plain), PDFTextStripper().getText(underlined))
                val a = PDFRenderer(underlined).renderImageWithDPI(0, 150f)
                val b = PDFRenderer(plain).renderImageWithDPI(0, 150f)
                var different = 0
                for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) different++
                assertTrue(different > 50)
                assertEquals(plain.getPage(0).annotations.size, underlined.getPage(0).annotations.size)
                val tokens = org.apache.pdfbox.pdfparser.PDFStreamParser(underlined.getPage(0)).parse()
                val matrices = tokens.indices.filter { (tokens[it] as? org.apache.pdfbox.contentstream.operator.Operator)?.name == "Tm" }
                val baseline = (tokens[matrices.first() - 1] as org.apache.pdfbox.cos.COSNumber).floatValue()
                for (i in tokens.indices.filter { (tokens[it] as? org.apache.pdfbox.contentstream.operator.Operator)?.name == "re" }) {
                    val bottom = (tokens[i - 3] as org.apache.pdfbox.cos.COSNumber).floatValue()
                    val height = (tokens[i - 1] as org.apache.pdfbox.cos.COSNumber).floatValue()
                    assertTrue(bottom + height < baseline, "Underline ink must lie below the text baseline")
                }
            }
        }
    }

    @Test fun `international URI paths are encoded for PDF actions while preserving the caller target`() {
        val target = PdfLink.External("https://example.test/café")
        val model = PdfDocument(page, listOf(paragraph("International URI", PdfTextNavigation(target))))
        Loader.loadPDF(PdfEngine().render(model, FoundationLetter.fonts())).use { pdf ->
            val actions = pdf.getPage(0).annotations.map { (it as PDAnnotationLink).action as PDActionURI }
            assertTrue(actions.isNotEmpty())
            assertTrue(actions.all { it.uri == "https://example.test/caf%C3%A9" })
        }
        assertEquals("https://example.test/café", target.uri)
        assertThrows(IllegalArgumentException::class.java) { PdfLink.External("https://example.test/" + "é".repeat(500)) }
    }

    @Test fun `unqualified bidi and frame navigation reject before silently losing destinations`() {
        val linked = paragraph("Linked text", PdfTextNavigation(PdfLink.External("https://example.test/")))
        val bidi = PdfParagraph(linked.runs, format = PdfParagraphFormat(textDirection = PdfTextDirection.LEFT_TO_RIGHT))
        val frame = PdfTextBox(30f, 30f, 200f, 60f, listOf(linked), PdfBoxAnchor.PAGE, rotation = 90f)
        for (block in listOf<PdfBlock>(bidi, frame)) {
            assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
                PdfEngine().render(PdfDocument(page, listOf(block), PdfFlow()), FoundationLetter.fonts())
            }.code)
        }
        val tab = PdfParagraph(listOf(PdfTextRun("Before", style), PdfTab(style.copy(underline = true)), PdfTextRun("After", style)))
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(PdfDocument(page, listOf(tab)), FoundationLetter.fonts())
        }.code)
    }

    @Test fun `underline metrics cannot silently paint outside the supplied font bounds`() {
        val bytes = FoundationLetter.fonts().first().bytes()
        val data = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        val count = data.getShort(4).toInt() and 65535
        val post = (0 until count).map { 12 + it * 16 }.single { data.getInt(it) == 0x706F7374 }
        data.putShort(data.getInt(post + 8) + 8, (-2048).toShort())
        val model = PdfDocument(page, listOf(PdfParagraph(listOf(PdfTextRun("Underline outside the line", style.copy(underline = true))))))
        assertEquals(PdfFailureCode.UNSUPPORTED_TEXT, assertThrows(PdfRenderException::class.java) {
            PdfEngine().render(model, listOf(PdfFont("regular", bytes)))
        }.code)
    }

    @Test fun `missing duplicate and excessive navigation cannot produce ambiguous output`() {
        val engine = PdfEngine()
        val missing = PdfDocument(page, listOf(paragraph("Missing", PdfTextNavigation(PdfLink.Internal("missing")))))
        assertThrows(IllegalArgumentException::class.java) { engine.render(missing, FoundationLetter.fonts()) }
        val destination = PdfTextNavigation(destinations = listOf(PdfDestination("same")))
        val duplicate = PdfDocument(page, listOf(paragraph("First", destination), paragraph("Second", destination)))
        assertThrows(IllegalArgumentException::class.java) { engine.render(duplicate, FoundationLetter.fonts()) }
        val large = PdfDocument(PdfPage(600f, 800f, PdfMargins(20f, 20f, 20f, 20f)), listOf(
            paragraph("a ".repeat(2_100), PdfTextNavigation(PdfLink.External("https://example.test/")))) , PdfFlow())
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, assertThrows(PdfRenderException::class.java) { engine.render(large, FoundationLetter.fonts()) }.code)
    }

    @Test fun `navigation and caller destinations are immutable and isolated across shared engine requests`() {
        val entries = mutableListOf(PdfDestination("Conclusion", "Conclusion"))
        val snapshot = PdfTextNavigation(destinations = entries)
        entries.clear()
        assertEquals(1, snapshot.destinations.size)
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.destinations as MutableList).clear() }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val engine = PdfEngine()
            val results = pool.invokeAll((1..8).map { index -> Callable {
                Loader.loadPDF(engine.render(document(), FoundationLetter.fonts())).use { pdf ->
                    assertEquals(setOf("Conclusion"), pdf.documentCatalog.names.dests.names.keys)
                    assertEquals("Conclusion", pdf.documentCatalog.documentOutline.firstChild.title)
                    index
                }
            } })
            assertEquals((1..8).toList(), results.map { it.get() })
        } finally { pool.shutdownNow() }
    }
}

package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PdfEngineTest {
    private fun document(text: String, width: Float = 595.28f, height: Float = 841.89f) =
        PdfDocument(PdfPage(width, height, PdfMargins(20f, 20f, 20f, 20f)),
            listOf(PdfParagraph(listOf(PdfTextRun(text, PdfTextStyle("regular", 11f))))))

    @Test fun `letter retains searchable Unicode styles embedded subsets and image geometry`() {
        val bytes = PdfEngine().render(FoundationLetter.document(), FoundationLetter.fonts())
        Loader.loadPDF(bytes).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            assertEquals(1.7f, pdf.version)
            assertFalse(pdf.isEncrypted)
            val page = pdf.getPage(0)
            assertEquals(595.28f, page.mediaBox.width, 0.01f)
            assertEquals(841.89f, page.mediaBox.height, 0.01f)
            val text = PDFTextStripper().getText(pdf).replace(Regex("\\s+"), " ")
            for (expected in listOf("café", "beëindiging", "coördinatie", "€ 1.234,56", "Deze tekst is vet.", "Deze tekst is cursief.", "office en affinity")) {
                assertTrue(text.contains(expected), "Missing searchable fixture text: $expected")
            }
            val fonts = page.resources.fontNames.map { page.resources.getFont(it) }
            assertEquals(3, fonts.size)
            assertTrue(fonts.all { it.isEmbedded && it.name.matches(Regex("[A-Z]{6}\\+.+")) })
            assertTrue(fonts.all { it.cosObject.getDictionaryObject(COSName.TO_UNICODE) != null })
            assertTrue(fonts.any { it.name.contains("Bold") })
            assertTrue(fonts.any { it.name.contains("Italic") })
            assertEquals(1, page.resources.xObjectNames.count { page.resources.getXObject(it) is PDImageXObject })
            val tokens = PDFStreamParser(page).parse()
            val matrix = tokens.indexOfFirst { it is Operator && it.name == "cm" }
            assertTrue(matrix >= 6)
            val coordinates = tokens.subList(matrix - 6, matrix).map { (it as COSNumber).floatValue() }
            assertEquals(listOf(54f, 0f, 0f, 54f, 56.7f, 731.19f), coordinates.map { kotlin.math.round(it * 100) / 100 })
        }
    }

    @Test fun `wrapping preserves words across style boundaries and stays inside page margins`() {
        val paragraph = PdfParagraph(listOf(PdfTextRun("office aff", PdfTextStyle("regular", 11f)),
            PdfTextRun("inity ", PdfTextStyle("bold", 11f)), PdfTextRun("café ".repeat(20), PdfTextStyle("regular", 11f))))
        val model = PdfDocument(PdfPage(250f, 500f, PdfMargins(20f, 20f, 20f, 20f)), listOf(paragraph))
        Loader.loadPDF(PdfEngine().render(model, FoundationLetter.fonts())).use { pdf ->
            val positions = mutableListOf<TextPosition>()
            val extractor = object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) { positions += text; super.processTextPosition(text) }
            }
            val text = extractor.getText(pdf).replace(Regex("\\s+"), " ").trim()
            assertEquals("office affinity " + List(20) { "café" }.joinToString(" "), text)
            assertTrue(positions.map { it.yDirAdj }.toSet().size > 1)
            assertTrue(positions.all { it.xDirAdj >= 19.99f && it.xDirAdj + it.widthDirAdj <= 230.01f })
            assertTrue(positions.all { it.yDirAdj + it.heightDir <= 480f })
        }
    }

    @Test fun `overflow and unsupported shaping are rejected without silent shrinking or substitutions`() {
        assertCode(PdfFailureCode.CONTENT_OVERFLOW) { PdfEngine().render(document("long ".repeat(500), 150f, 90f), FoundationLetter.fonts()) }
        assertCode(PdfFailureCode.CONTENT_OVERFLOW) { PdfEngine().render(document("W".repeat(100)), FoundationLetter.fonts()) }
        for (text in listOf("مرحبا", "cafe\u0301", "bad\ttext", "bad\ntext", "bad\u200Dtext", "\uD800")) {
            assertCode(PdfFailureCode.UNSUPPORTED_TEXT) { PdfEngine().render(document(text), FoundationLetter.fonts()) }
        }
        assertCode(PdfFailureCode.MISSING_GLYPH) { PdfEngine().render(document("\uD83D\uDE80"), FoundationLetter.fonts()) }
        assertCode(PdfFailureCode.FONT_UNAVAILABLE) { PdfEngine().render(document("test"), listOf(PdfFont("other", FoundationLetter.fonts()[0].bytes()))) }
    }

    @Test fun `embedding restrictions malformed resources and pixel budgets fail explicitly`() {
        val original = FoundationLetter.fonts()[0].bytes()
        val buffer = ByteBuffer.wrap(original).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.getShort(4).toInt() and 65535
        val os2 = (0 until count).map { 12 + it * 16 }.first { buffer.getInt(it) == 0x4F532F32 }
        val permissionOffset = buffer.getInt(os2 + 8) + 8
        for (flags in listOf(1, 2, 0x100, 0x200)) {
            val bytes = original.copyOf()
            ByteBuffer.wrap(bytes).putShort(permissionOffset, flags.toShort())
            assertCode(PdfFailureCode.FONT_EMBEDDING_FORBIDDEN) { PdfEngine().render(document("test"), listOf(PdfFont("regular", bytes))) }
        }
        assertCode(PdfFailureCode.INVALID_RESOURCE) { PdfEngine().render(document("test"), listOf(PdfFont("regular", ByteArray(12)))) }
        val page = PdfPage(595.28f, 841.89f, PdfMargins(20f, 20f, 20f, 20f))
        assertCode(PdfFailureCode.INVALID_RESOURCE) { PdfEngine().render(PdfDocument(page, listOf(PdfImage(ByteArray(24), 20f, 20f))), FoundationLetter.fonts()) }
        val huge = FoundationLetter.resource("/fixtures/nuaforma-icon-transparent.png")
        ByteBuffer.wrap(huge).putInt(16, 8192).putInt(20, 8192)
        assertCode(PdfFailureCode.RESOURCE_LIMIT) { PdfEngine().render(PdfDocument(page, listOf(PdfImage(huge, 20f, 20f))), FoundationLetter.fonts()) }
        assertThrows(IllegalArgumentException::class.java) { PdfPage(Float.NaN, 800f, PdfMargins(1f, 1f, 1f, 1f)) }
        assertThrows(IllegalArgumentException::class.java) { PdfEngine().render(document("test"), listOf(FoundationLetter.fonts()[0], FoundationLetter.fonts()[0])) }
    }

    @Test fun `resources are defensive snapshots and concurrent renders do not leak content`() {
        val bytes = FoundationLetter.fonts()[0].bytes()
        val font = PdfFont("regular", bytes)
        bytes.fill(0)
        font.bytes().fill(0)
        val blocks = mutableListOf<PdfBlock>(PdfParagraph(listOf(PdfTextRun("snapshot", PdfTextStyle("regular", 11f)))))
        val model = PdfDocument(PdfPage(300f, 200f, PdfMargins(20f, 20f, 20f, 20f)), blocks)
        blocks.clear()
        Loader.loadPDF(PdfEngine().render(model, listOf(font))).use { assertTrue(PDFTextStripper().getText(it).contains("snapshot")) }
        val executor = Executors.newFixedThreadPool(3)
        try {
            val engine = PdfEngine()
            val fonts = FoundationLetter.fonts()
            val results = (1..6).map { number -> executor.submit<String> {
                Loader.loadPDF(engine.render(document("Request $number"), fonts)).use { PDFTextStripper().getText(it).trim() }
            } }
            assertEquals((1..6).map { "Request $it" }, results.map { it.get(20, TimeUnit.SECONDS) })
        } finally { executor.shutdownNow() }
    }

    @Test fun `interrupted work fails without clearing the caller interrupt flag`() {
        Thread.currentThread().interrupt()
        try {
            assertCode(PdfFailureCode.CANCELLED) { PdfEngine().render(document("test"), FoundationLetter.fonts()) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `space-only paragraphs consume vertical space and oversized leading spaces fail`() {
        val page = PdfPage(200f, 70f, PdfMargins(20f, 20f, 20f, 20f))
        val style = PdfTextStyle("regular", 11f)
        val content = PdfDocument(page, listOf(PdfParagraph(listOf(PdfTextRun("   ", style))),
            PdfParagraph(listOf(PdfTextRun("visible", style)))))
        assertCode(PdfFailureCode.CONTENT_OVERFLOW) { PdfEngine().render(content, FoundationLetter.fonts()) }
        assertCode(PdfFailureCode.CONTENT_OVERFLOW) { PdfEngine().render(document(" ".repeat(1_000) + "text"), FoundationLetter.fonts()) }
    }

    private fun assertCode(code: PdfFailureCode, action: () -> Unit) {
        assertEquals(code, assertThrows(PdfRenderException::class.java, action).code)
    }
}

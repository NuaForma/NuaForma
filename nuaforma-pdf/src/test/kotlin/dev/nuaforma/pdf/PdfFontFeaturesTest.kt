package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PdfFontFeaturesTest {
    @TempDir lateinit var temporary: Path
    private val text = "AVATAR office fi ﬁ fl ﬂ ffi ﬃ A\u00a0A"
    private val features = PdfFontFeatures(PdfKerning.OPENTYPE, true)

    private fun font(embedding: PdfFontEmbedding = PdfFontEmbedding.SUBSET) = PdfFont("regular", FoundationLetter.resource("/fonts/NotoSerif-Regular.ttf"), embedding)

    private fun model(value: String = text): PdfDocument {
        val plain = PdfTextStyle("regular", 20f)
        return PdfDocument(PdfPage(595.28f, 841.89f, PdfMargins(36f, 36f, 36f, 36f)), listOf(PdfParagraph(
            listOf(PdfTextRun("Plain ", plain), PdfTextRun(value, plain.withFontFeatures(features))), 0f, 0f, PdfParagraphFormat(exactLineHeight = 40f))))
    }

    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()

    private fun validator() = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
        Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { files -> files.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)

    @Test fun `subset and full embedding preserve glyph aliases and logical ligatures in all four validated profiles`() {
        val verifier = validator()
        val engine = PdfEngine(verifier)
        for (embedding in PdfFontEmbedding.entries) for (profile in PdfOutputProfile.entries) {
            val bytes = engine.render(model(), listOf(font(embedding)), PdfOutputOptions(profile))
            if (profile == PdfOutputProfile.STANDARD) verifier.validateStructure(bytes, profile, PdfRenderControl())
            Loader.loadPDF(bytes).use { pdf ->
                assertEquals(normalized("Plain $text"), normalized(PDFTextStripper().getText(pdf)), "$embedding $profile")
                val page = pdf.getPage(0)
                val font = page.resources.getFont(page.resources.fontNames.first()) as PDType0Font
                assertTrue(font.isEmbedded)
                assertEquals(embedding == PdfFontEmbedding.SUBSET, font.name.matches(Regex("[A-Z]{6}\\+.*")))
                val codes = PDFStreamParser(page).parse().filterIsInstance<COSString>().flatMap { value ->
                    value.bytes.toList().chunked(2).map { ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255) }
                }.distinct()
                val logical = codes.map { font.toUnicode(it) }.toSet()
                assertTrue(logical.containsAll(listOf("fi", "ﬁ", " ", "\u00a0")), "$embedding $profile aliases: $logical")
                val aliases = codes.filter { font.toUnicode(it) in setOf("fi", "ﬁ") }
                assertEquals(2, aliases.size)
                assertEquals(1, aliases.map { font.codeToGID(it) }.distinct().size)
                assertTrue(aliases.all { !font.getPath(it).bounds2D.isEmpty })
                if (embedding == PdfFontEmbedding.SUBSET) {
                    val cidMap = font.descendantFont.cosObject.getDictionaryObject(COSName.CID_TO_GID_MAP) as COSStream
                    val count = cidMap.createInputStream().use { it.readAllBytes().size / 2 }
                    val cidSet = font.fontDescriptor.cosObject.getDictionaryObject(COSName.CID_SET) as COSStream
                    val bits = cidSet.createInputStream().use { it.readAllBytes() }
                    assertEquals((count + 7) / 8, bits.size)
                    assertTrue((0 until count).all { (bits[it / 8].toInt() and (128 ushr (it % 8))) != 0 })
                }
            }
        }
        assertEquals(0, Files.list(temporary).use { it.count() })
    }

    @Test fun `watermarks share aliased shaped fonts for standard two-b and three-b and one-b still refuses`() {
        val engine = PdfEngine(validator())
        val watermark = PdfWatermark("fi ﬁ", "regular", opacity = .3f)
        for (profile in listOf(PdfOutputProfile.STANDARD, PdfOutputProfile.PDFA_2B, PdfOutputProfile.PDFA_3B)) {
            Loader.loadPDF(engine.render(model(), listOf(font()), PdfOutputOptions(profile), PdfRenderControl(), watermark)).use { pdf ->
                assertTrue(normalized(PDFTextStripper().getText(pdf)).contains(normalized("Plain $text")))
            }
        }
        assertEquals(PdfFailureCode.UNSUPPORTED_OUTPUT, assertThrows(PdfRenderException::class.java) {
            engine.render(model(), listOf(font()), PdfOutputOptions(PdfOutputProfile.PDFA_1B), PdfRenderControl(), watermark)
        }.code)
    }

    @Test fun `full fonts with unused supplementary coverage keep ordinary one-b text at PDF fourteen`() {
        val paragraph = PdfParagraph(listOf(PdfTextRun("Plain office fi ﬁ", PdfTextStyle("regular", 20f))))
        val document = PdfDocument(PdfPage(595.28f, 841.89f, PdfMargins(36f, 36f, 36f, 36f)), listOf(paragraph))
        val bytes = PdfEngine(validator()).render(document, listOf(font(PdfFontEmbedding.FULL)), PdfOutputOptions(PdfOutputProfile.PDFA_1B))
        Loader.loadPDF(bytes).use { pdf ->
            assertEquals(1.4f, pdf.version)
            assertEquals(normalized("Plain office fi ﬁ"), normalized(PDFTextStripper().getText(pdf)))
            val embedded = pdf.getPage(0).resources.let { it.getFont(it.fontNames.first()) }
            assertArrayEquals(font(PdfFontEmbedding.FULL).bytes(), embedded.fontDescriptor.fontFile2.toByteArray())
        }
    }

    @Test fun `shared engines keep font glyph and Unicode state private across concurrent font families`() {
        val executor = Executors.newFixedThreadPool(4)
        val engine = PdfEngine()

        fun render(number: Int): Pair<String, String> {
            val fonts = if (number % 2 == 0) listOf(font()) else listOf(PdfFont("regular", FoundationLetter.resource("/fonts/LiberationSans-Regular.ttf")))
            return Loader.loadPDF(engine.render(model("Request$number AVATAR office fi"), fonts)).use { pdf ->
                val image = PDFRenderer(pdf).renderImageWithDPI(0, 96f)
                val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                val buffer = ByteBuffer.allocate(pixels.size * 4)
                buffer.asIntBuffer().put(pixels)
                normalized(PDFTextStripper().getText(pdf)) to MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
            }
        }

        val expected = (1..8).map(::render)
        try {
            val requests = (1..8).map { number -> executor.submit<Pair<String, String>> { render(number) } }
            requests.forEachIndexed { index, result ->
                assertEquals("Plain Request${index + 1} AVATAR office fi", expected[index].first)
                assertEquals(expected[index], result.get(30, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun `shaping observes cancellation and output budgets without polluting a later render`() {
        val checks = AtomicInteger()
        val control = PdfRenderControl(checkpoint = Runnable { if (checks.incrementAndGet() >= 8) throw PdfRenderException(PdfFailureCode.CANCELLED, "test cancellation") })
        val engine = PdfEngine()
        assertEquals(PdfFailureCode.CANCELLED, assertThrows(PdfRenderException::class.java) { engine.render(model(), listOf(font()), PdfOutputOptions(), control) }.code)
        assertEquals(PdfFailureCode.RESOURCE_LIMIT, assertThrows(PdfRenderException::class.java) {
            engine.render(model(), listOf(font()), PdfOutputOptions(), PdfRenderControl(maxOutputBytes = 512))
        }.code)
        Loader.loadPDF(engine.render(model(), listOf(font()))).use { assertEquals(normalized("Plain $text"), normalized(PDFTextStripper().getText(it))) }
    }

}

package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import java.awt.image.IndexColorModel
import java.util.concurrent.atomic.AtomicReference

class PdfOutputTest {
    @TempDir lateinit var temporary: Path

    private fun validator(timeout: Long = 30_000) = PdfProcessValidator(
        Path.of(System.getProperty("nuaforma.pdf.validation.java")),
        Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".jar") }.sorted().toList()
        }, temporary, timeout)

    private fun model(text: String = "café Ελληνικά Русский €") = PdfDocument(
        PdfPage(595.28f, 841.89f, PdfMargins(36f, 36f, 36f, 36f)),
        listOf(PdfParagraph(listOf(PdfTextRun(text, PdfTextStyle("regular", 11f))))))

    private fun code(expected: PdfFailureCode, action: () -> Unit) =
        assertEquals(expected, assertThrows(PdfRenderException::class.java, action).code)

    private fun imageModel(alpha: Boolean) = PdfDocument(model().page, listOf(
        PdfImage(ByteArrayOutputStream().use { out ->
            val image = BufferedImage(8, 8, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
            for (y in 0..7) for (x in 0..7) image.setRGB(x, y, if (alpha) 0x80008b90.toInt() else 0xff008b90.toInt())
            ImageIO.write(image, "png", out); out.toByteArray()
        }, 32f, 32f), model().blocks.single()))

    @Test fun `all archival profiles pass independent exact flavour validation with embedded Unicode fonts`() {
        val engine = PdfEngine(validator())
        for (profile in PdfOutputProfile.entries.filter { it != PdfOutputProfile.STANDARD }) {
            val bytes = engine.render(model(), FoundationLetter.fonts(), PdfOutputOptions(profile, "Rapport & <α>", "NuaForma"))
            Loader.loadPDF(bytes).use { pdf ->
                assertEquals(if (profile == PdfOutputProfile.PDFA_1B) 1.4f else 1.7f, pdf.version)
                assertEquals("café Ελληνικά Русский €", PDFTextStripper().getText(pdf).trim())
                assertEquals("Rapport & <α>", pdf.documentInformation.title)
                assertEquals(1, pdf.documentCatalog.outputIntents.size)
                val xmp = pdf.documentCatalog.metadata.exportXMPMetadata().use { it.readAllBytes().toString(Charsets.UTF_8) }
                assertTrue(xmp.contains("<pdfaid:conformance>B</pdfaid:conformance>"))
                val font = pdf.getPage(0).resources.fontNames.first().let { pdf.getPage(0).resources.getFont(it) }
                assertTrue(font.isEmbedded)
                assertNotNull(font.cosObject.getDictionaryObject(COSName.TO_UNICODE))
            }
        }
        assertEquals(0, Files.list(temporary).use { it.count() })
    }

    @Test fun `standard remains PDF 17 without archival metadata and never invokes validator`() {
        val bytes = PdfEngine { _, _ -> fail("Standard PDF must not invoke the archival validator") }.render(model(), FoundationLetter.fonts())
        Loader.loadPDF(bytes).use { pdf ->
            assertEquals(1.7f, pdf.version)
            assertNull(pdf.documentCatalog.metadata)
            assertTrue(pdf.documentCatalog.outputIntents.isEmpty())
            assertFalse(pdf.isEncrypted)
            assertEquals("café Ελληνικά Русский €", PDFTextStripper().getText(pdf).trim())
        }
    }

    @Test fun `single strikeout and combined underlining preserve searchable Unicode and pass every archival profile`() {
        val style = PdfTextStyle("regular", 13f, PdfColor(20, 50, 90), underline = true).withStrikethrough(true)
        val model = PdfDocument(model().page, listOf(PdfParagraph(listOf(PdfTextRun("Café Русский crossed", style)))))
        val engine = PdfEngine(validator())
        for (profile in PdfOutputProfile.entries) {
            val bytes = engine.render(model, FoundationLetter.fonts(), PdfOutputOptions(profile))
            Loader.loadPDF(bytes).use { pdf ->
                assertEquals("Café Русский crossed", PDFTextStripper().getText(pdf).trim())
                assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
                val tokens = org.apache.pdfbox.pdfparser.PDFStreamParser(pdf.getPage(0)).parse()
                val glyphSpans = tokens.count { it is org.apache.pdfbox.contentstream.operator.Operator && it.name == "Tj" }
                assertTrue(glyphSpans > 0)
                assertEquals(2 * glyphSpans, tokens.count { it is org.apache.pdfbox.contentstream.operator.Operator && it.name == "re" })
            }
        }
        assertEquals(0, Files.list(temporary).use { it.count() })
    }

    @Test fun `opaque RGB images pass all archival profiles while transparency is rejected only for 1b`() {
        val engine = PdfEngine(validator())
        for (profile in PdfOutputProfile.entries.filter { it != PdfOutputProfile.STANDARD }) {
            assertTrue(engine.render(imageModel(false), FoundationLetter.fonts(), PdfOutputOptions(profile)).isNotEmpty())
            if (profile == PdfOutputProfile.PDFA_1B) {
                code(PdfFailureCode.UNSUPPORTED_OUTPUT) { engine.render(imageModel(true), FoundationLetter.fonts(), PdfOutputOptions(profile)) }
                code(PdfFailureCode.UNSUPPORTED_OUTPUT) { engine.render(FoundationLetter.document(), FoundationLetter.fonts(), PdfOutputOptions(profile)) }
            } else assertTrue(engine.render(imageModel(true), FoundationLetter.fonts(), PdfOutputOptions(profile)).isNotEmpty())
        }
        assertEquals(0, Files.list(temporary).use { it.count() })
    }

    @Test fun `complete font embedding preserves the complete supplied font and searchable Unicode for each profile`() {
        val original = FoundationLetter.fonts()[0].bytes()
        val font = PdfFont("regular", original, PdfFontEmbedding.FULL)
        for (profile in PdfOutputProfile.entries) {
            val bytes = PdfEngine(validator()).render(model(), listOf(font), PdfOutputOptions(profile))
            Loader.loadPDF(bytes).use { pdf ->
                val embedded = pdf.getPage(0).resources.fontNames.first().let { pdf.getPage(0).resources.getFont(it) }
                assertTrue(embedded.isEmbedded)
                assertFalse(embedded.name.contains('+'))
                assertArrayEquals(original, embedded.fontDescriptor.fontFile2.createInputStream().use { it.readAllBytes() })
                assertEquals("café Ελληνικά Русский €", PDFTextStripper().getText(pdf).trim())
            }
        }
    }

    @Test fun `no subsetting permission requires full embedding and never overrides embedding prohibitions`() {
        val original = FoundationLetter.fonts()[0].bytes()
        val buffer = ByteBuffer.wrap(original)
        val count = buffer.getShort(4).toInt() and 65535
        val os2 = (0 until count).map { 12 + it * 16 }.first { buffer.getInt(it) == 0x4F532F32 }
        val offset = buffer.getInt(os2 + 8) + 8
        for (flags in listOf(0x100, 2, 0x200, 0x102)) {
            val modified = original.copyOf().also { ByteBuffer.wrap(it).putShort(offset, flags.toShort()) }
            code(PdfFailureCode.FONT_EMBEDDING_FORBIDDEN) { PdfEngine().render(model(), listOf(PdfFont("regular", modified))) }
            if (flags == 0x100) {
                assertTrue(PdfEngine(validator()).render(model(), listOf(PdfFont("regular", modified, PdfFontEmbedding.FULL)), PdfOutputOptions(PdfOutputProfile.PDFA_2B)).isNotEmpty())
            } else code(PdfFailureCode.FONT_EMBEDDING_FORBIDDEN) { PdfEngine().render(model(), listOf(PdfFont("regular", modified, PdfFontEmbedding.FULL))) }
        }
    }

    @Test fun `archival output is not returned without validation or after failure or mutation`() {
        val options = PdfOutputOptions(PdfOutputProfile.PDFA_2B)
        code(PdfFailureCode.VALIDATION_UNAVAILABLE) { PdfEngine().render(model(), FoundationLetter.fonts(), options) }
        code(PdfFailureCode.VALIDATION_FAILED) { PdfEngine { _, _ -> error("Failure") }.render(model(), FoundationLetter.fonts(), options) }
        code(PdfFailureCode.VALIDATION_FAILED) { PdfEngine { bytes, _ -> bytes[0] = 0 }.render(model(), FoundationLetter.fonts(), options) }
    }

    @Test fun `independent validator rejects standard bytes malformed bytes wrong profile and cleans files`() {
        val verifier = validator()
        code(PdfFailureCode.VALIDATION_FAILED) { verifier.validate(PdfEngine().render(model(), FoundationLetter.fonts()), PdfOutputProfile.PDFA_2B) }
        code(PdfFailureCode.VALIDATION_FAILED) { verifier.validate("not a PDF".toByteArray(), PdfOutputProfile.PDFA_1B) }
        val valid = PdfEngine(verifier).render(model(), FoundationLetter.fonts(), PdfOutputOptions(PdfOutputProfile.PDFA_2B))
        code(PdfFailureCode.VALIDATION_FAILED) { verifier.validate(valid, PdfOutputProfile.PDFA_3B) }
        assertEquals(0, Files.list(temporary).use { it.count() })
    }

    @Test fun `timeout and preexisting interruption fail closed with no retained temporary files`() {
        code(PdfFailureCode.VALIDATION_FAILED) { validator(1).validate("%PDF-1.4".toByteArray(), PdfOutputProfile.PDFA_1B) }
        Thread.currentThread().interrupt()
        try {
            code(PdfFailureCode.CANCELLED) { validator().validate("%PDF-1.4".toByteArray(), PdfOutputProfile.PDFA_1B) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertEquals(0, Files.list(temporary).use { it.count() })
    }

    @Test fun `archival renders share the engine and validator without cross request state`() {
        val engine = PdfEngine(validator())
        val executor = Executors.newFixedThreadPool(3)
        try {
            val profiles = listOf(PdfOutputProfile.PDFA_1B, PdfOutputProfile.PDFA_2B, PdfOutputProfile.PDFA_3B)
            val results = profiles.mapIndexed { index, profile -> executor.submit<String> {
                val fonts = FoundationLetter.fonts().map { PdfFont(it.id, it.bytes(), if (index == 1) PdfFontEmbedding.FULL else PdfFontEmbedding.SUBSET) }
                Loader.loadPDF(engine.render(model("Request $index"), fonts, PdfOutputOptions(profile))).use { PDFTextStripper().getText(it).trim() }
            } }
            assertEquals(listOf("Request 0", "Request 1", "Request 2"), results.map { it.get(40, TimeUnit.SECONDS) })
        } finally { executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS) }
        assertEquals(0, Files.list(temporary).use { it.count() })
    }

    @Test fun `vendor validator parser is absent from hosting runtime`() {
        assertThrows(ClassNotFoundException::class.java) { Class.forName("org.verapdf.pdfa.Foundries") }
        assertThrows(IllegalArgumentException::class.java) { PdfOutputOptions(title = "bad\u0001") }
        assertThrows(IllegalArgumentException::class.java) { PdfOutputOptions(author = "\uD800") }
    }

    @Test fun `indexed PNG transparency also rejects in 1b and validates in 2b`() {
        val colors = IndexColorModel(8, 2, byteArrayOf(0, 0), byteArrayOf(0, 139.toByte()), byteArrayOf(0, 144.toByte()), byteArrayOf(0, 255.toByte()))
        val image = BufferedImage(8, 8, BufferedImage.TYPE_BYTE_INDEXED, colors)
        for (y in 0..7) for (x in 0..7) image.raster.setSample(x, y, 0, if (x < 4) 0 else 1)
        val png = ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
        assertEquals(3, png[25].toInt())
        assertTrue(png.toString(Charsets.ISO_8859_1).contains("tRNS"))
        val document = PdfDocument(model().page, listOf(PdfImage(png, 32f, 32f), model().blocks.single()))
        val engine = PdfEngine(validator())
        code(PdfFailureCode.UNSUPPORTED_OUTPUT) { engine.render(document, FoundationLetter.fonts(), PdfOutputOptions(PdfOutputProfile.PDFA_1B)) }
        assertTrue(engine.render(document, FoundationLetter.fonts(), PdfOutputOptions(PdfOutputProfile.PDFA_2B)).isNotEmpty())
    }

    @Test fun `interrupting active child validation reaps the child removes input and retains interruption`() {
        val verifier = validator()
        val bytes = PdfEngine().render(model(), FoundationLetter.fonts())
        val outcome = AtomicReference<PdfFailureCode>()
        val retained = AtomicReference<Boolean>()
        val worker = Thread {
            try { verifier.validate(bytes, PdfOutputProfile.PDFA_2B) }
            catch (failure: PdfRenderException) { outcome.set(failure.code) }
            finally { retained.set(Thread.currentThread().isInterrupted) }
        }
        worker.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var children: List<ProcessHandle>
            do {
                children = ProcessHandle.current().children().use { it.toList() }
                if (children.isEmpty()) Thread.sleep(5)
            } while (children.isEmpty() && worker.isAlive && System.nanoTime() < deadline)
            assertTrue(children.isNotEmpty(), "Expected the actual validator child to start")
            worker.interrupt(); worker.join(7_000)
            assertFalse(worker.isAlive)
            assertEquals(PdfFailureCode.CANCELLED, outcome.get())
            assertEquals(true, retained.get())
            assertTrue(children.none { it.isAlive })
            assertEquals(0, Files.list(temporary).use { it.count() })
        } finally { worker.interrupt(); worker.join(7_000) }
    }

    @Test fun `validator callback interruption maps to cancellation with retained flag`() {
        try {
            code(PdfFailureCode.CANCELLED) {
                PdfEngine { _, _ -> throw InterruptedException() }.render(model(), FoundationLetter.fonts(), PdfOutputOptions(PdfOutputProfile.PDFA_3B))
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}

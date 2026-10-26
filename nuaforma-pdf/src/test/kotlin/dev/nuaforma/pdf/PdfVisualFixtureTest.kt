package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.function.Executable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.imageio.ImageIO

/** Real engine output compared with reviewed golden images, plus independent content/structure checks. */
class PdfVisualFixtureTest {
    private val diagnostics = Path.of("build", "visual-regression", UUID.randomUUID().toString())
    private data class Page(val case: String, val page: Int, val width: Int, val height: Int, val sha256: String)
    private fun resource(name: String) = FoundationLetter.resource("/visual-goldens/$name")
    private fun pages() = resource("pages.tsv").toString(Charsets.UTF_8).lineSequence().drop(1).filter { it.isNotBlank() }.map {
        val columns = it.split('\t')
        require(columns.size == 5)
        Page(columns[0], columns[1].toInt(), columns[2].toInt(), columns[3].toInt(), columns[4])
    }.toList()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    @Test fun `candidate generation refuses golden resources and existing output directories`() {
        for (path in listOf("src/test/resources/visual-goldens", "src/test/resources/visual-goldens/forbidden", "build")) {
            assertThrows(IllegalArgumentException::class.java) { RenderPdfVisualFixtures.main(arrayOf(path)) }
        }
    }

    @Test fun `goldens match the reviewed inventory and fixed input resources`() {
        val pages = pages()
        val cases = PdfVisualFixtures.cases()
        assertEquals(cases.map { it.id }.toSet(), pages.map { it.case }.toSet())
        assertEquals(pages.size, pages.map { it.case to it.page }.toSet().size)
        for (case in cases) assertEquals((1..case.pages).toList(), pages.filter { it.case == case.id }.map { it.page })
        for (page in pages) {
            val name = "${page.case}-page-${page.page}.png"
            val bytes = resource(name)
            assertEquals(page.sha256, sha256(bytes), "Golden changed without a reviewed manifest update: $name")
            val image = requireNotNull(ImageIO.read(bytes.inputStream()))
            assertEquals(page.width, image.width, name)
            assertEquals(page.height, image.height, name)
        }
        resource("inputs.sha256").toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach {
            val parts = it.split("  ", limit = 2)
            assertEquals(parts[0], sha256(FoundationLetter.resource(parts[1])), "Golden input changed: ${parts[1]}")
        }
    }

    @TestFactory fun `engine fixtures retain golden appearance content geometry and embedded fonts`() = PdfVisualFixtures.cases().map { case ->
        DynamicTest.dynamicTest(case.id) {
            val bytes = PdfEngine().render(case.document, FoundationLetter.fonts(), PdfOutputOptions(), PdfRenderControl(), case.watermark)
            val output = diagnostics.resolve(case.id)
            try {
                Loader.loadPDF(bytes).use { pdf ->
                    assertEquals(case.pages, pdf.numberOfPages)
                    val text = RenderPdfVisualFixtures.extractedText(pdf)
                    case.requiredText.forEach { assertTrue(text.contains(it), "${case.id}: missing $it") }
                    val scale = RenderPdfVisualFixtures.DPI / 72f
                    val renderer = RenderPdfVisualFixtures.renderer(pdf)
                    assertAll(case.id, pages().filter { it.case == case.id }.map { page -> Executable {
                        val actual = pdf.getPage(page.page - 1)
                        val expectedImage = requireNotNull(ImageIO.read(resource("${case.id}-page-${page.page}.png").inputStream()))
                        PdfGoldenImages.assertMatches(expectedImage, RenderPdfVisualFixtures.rasterize(renderer, page.page - 1),
                            output.resolve("page-${page.page}"), "${case.id}, page ${page.page}")
                        assertEquals(page.width, (actual.mediaBox.width * scale).toInt())
                        assertEquals(page.height, (actual.mediaBox.height * scale).toInt())
                        assertEquals(0, actual.rotation)
                        assertTrue(actual.resources.fontNames.toList().isNotEmpty())
                        actual.resources.fontNames.forEach { assertTrue(actual.resources.getFont(it).isEmbedded) }
                    } })
                }
            } catch (failure: AssertionError) {
                try {
                    Files.createDirectories(output)
                    Files.write(output.resolve("actual.pdf"), bytes)
                } catch (diagnosticFailure: Exception) {
                    failure.addSuppressed(diagnosticFailure)
                }
                throw failure
            }
        }
    }
}

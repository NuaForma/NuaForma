package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.*

class OwnEnginePageFormatsTest {
    private val sizes = mapOf("a4" to (595.3f to 841.9f), "a3" to (841.9f to 1190.55f), "letter" to (612f to 792f), "legal" to (612f to 1008f))
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun fixture(kind: String) = resource("/own-engine-page-formats/$kind/source.docx")
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, name) -> PdfFont(id, resource("/own-engine-fonts/LiberationSans-$name.ttf")) }
    private fun dimensions(kind: String): Pair<Float, Float> {
        val value = sizes.getValue(kind.substringBefore('-'))
        return if (kind.endsWith("landscape")) value.second to value.first else value
    }

    @ParameterizedTest
    @CsvSource("a4-portrait", "a4-landscape", "a3-portrait", "a3-landscape", "letter-portrait", "letter-landscape", "legal-portrait", "legal-landscape")
    fun `source paper dimensions margins text and page bands survive actual PDFs`(kind: String) {
        val model = OwnEngineDocxReader().read(fixture(kind)); val (width, height) = dimensions(kind)
        assertEquals(width, model.page.width, .001f); assertEquals(height, model.page.height, .001f)
        assertEquals(if (kind.startsWith("legal")) PdfMargins(90f,45f,70f,75f) else PdfMargins(90f,56.7f,60f,56.7f), model.page.margins)
        Loader.loadPDF(PdfEngine().render(model, fonts())).use { pdf ->
            assertTrue(pdf.numberOfPages in 2..8)
            for (page in pdf.pages) {
                assertEquals(width, page.mediaBox.width, .001f); assertEquals(height, page.mediaBox.height, .001f); assertEquals(0, page.rotation)
                assertTrue(page.resources.xObjectNames.any { page.resources.getXObject(it) is PDImageXObject })
                assertTrue(page.resources.fontNames.all { page.resources.getFont(it).isEmbedded })
            }
            val text = object : PDFTextStripper() {
                override fun processTextPosition(position: TextPosition) {
                    assertTrue(position.xDirAdj >= -.1f && position.xDirAdj + position.widthDirAdj <= width + .1f)
                    assertTrue(position.yDirAdj in 0f..height)
                    super.processTextPosition(position)
                }
            }.getText(pdf)
            for (n in 1..28) assertEquals(1, Regex("\\bW${n.toString().padStart(2,'0')}\\b").findAll(text).count(), "$kind row $n")
            for (value in listOf("Київ, Україна", "Отчёт о ходе проекта.", "Έκθεση έργου", "1 234,56 грн.", "Na de overdracht volgt", "Het team zet de afgesproken werkzaamheden voort.")) assertTrue(text.contains(value), "$kind $value")
            for (n in 1..pdf.numberOfPages) assertTrue(text.contains("Pagina $n"), "$kind page $n")
        }
    }

    @Test fun `physical sizes without orientation remain supported and conflicts reject`() {
        for (kind in listOf("a4-portrait", "letter-landscape")) {
            val noFlag = change(kind) { it.replace(Regex(" w:orient=\"(?:portrait|landscape)\""), "") }
            val model = OwnEngineDocxReader().read(noFlag); val (width, height) = dimensions(kind)
            assertEquals(width, model.page.width); assertEquals(height, model.page.height)
        }
        for (replacement in listOf("landscape", "sideways", "Portrait")) assertThrows(IllegalArgumentException::class.java) {
            OwnEngineDocxReader().read(change("a4-portrait") { it.replace("w:orient=\"portrait\"", "w:orient=\"$replacement\"") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            OwnEngineDocxReader().read(change("letter-landscape") { it.replace("w:orient=\"landscape\"", "w:orient=\"portrait\"") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            OwnEngineDocxReader().read(change("a4-portrait") { it.replace("w:w=\"11906\" w:h=\"16838\"", "w:w=\"0\" w:h=\"16838\"") })
        }
    }

    @Test fun `shared engine keeps dimensions separate across concurrent paper formats`() {
        val pool = Executors.newFixedThreadPool(4); val engine = PdfEngine(); val fonts = fonts()
        val kinds = sizes.keys.flatMap { listOf("$it-portrait", "$it-landscape") }
        try {
            val jobs = kinds.map { kind -> pool.submit<Pair<Float,Float>> {
                Loader.loadPDF(engine.render(OwnEngineDocxReader().read(fixture(kind)), fonts)).use { it.getPage(0).mediaBox.let { box -> box.width to box.height } }
            } }
            jobs.forEachIndexed { i, job -> assertEquals(dimensions(kinds[i]), job.get(60, TimeUnit.SECONDS)) }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS)) }
    }

    private fun change(kind: String, transform: (String) -> String): ByteArray {
        val result = ByteArrayOutputStream()
        ZipOutputStream(result).use { output -> ZipInputStream(fixture(kind).inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break; val bytes = input.readBytes(); output.putNextEntry(ZipEntry(entry.name))
                output.write(if (entry.name == "word/document.xml") transform(bytes.toString(Charsets.UTF_8)).also { require(it != bytes.toString(Charsets.UTF_8)) }.toByteArray(Charsets.UTF_8) else bytes)
                output.closeEntry()
            }
        } }
        return result.toByteArray()
    }
}

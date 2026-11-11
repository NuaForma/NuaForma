package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch

/** Frozen, independent Word exports. Candidates may be regenerated; references are never rewritten. */
internal object WordLayoutOracle {

    fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun fonts(family: String): List<PdfFont> = if (family == "Noto Serif") listOf(PdfFont("regular", resource("/own-engine-fonts/NotoSerif-Regular.ttf")))
        else listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "bolditalic" to "BoldItalic")
        .map { (id, face) -> PdfFont(id, resource("/own-engine-fonts/LiberationSans-$face.ttf")) }

    fun render(source: ByteArray, family: String = "Liberation Sans"): ByteArray = PdfEngine().render(OwnEngineDocxReader(family).read(source), fonts(family))

    data class Position(val page: Int, val x: Float, val y: Float, val size: Float, val face: String, val rgb: Int)

    fun positions(pdf: PDDocument, marker: Regex): Map<String, Position> {
        val result = linkedMapOf<String, Position>()
        val colors = java.util.IdentityHashMap<TextPosition, Int>()
        object : PDFTextStripper() {
            init {
                addOperator(org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor(this))
                addOperator(org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor(this))
                addOperator(org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor(this))
                addOperator(org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace(this))
                addOperator(org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor(this))
                addOperator(org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN(this))
            }

            override fun processTextPosition(position: TextPosition) {
                colors[position] = graphicsState.nonStrokingColor.toRGB()
                super.processTextPosition(position)
            }

            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                val characters = textPositions.flatMap { position -> List(position.unicode.length) { position } }
                val raw = textPositions.joinToString("") { it.unicode }
                Regex(marker.pattern.removePrefix("^")).findAll(raw).forEach { match ->
                    val first = characters[match.range.first]
                    assertNull(result.put(match.groupValues[1], Position(currentPageNo, first.xDirAdj, first.yDirAdj,
                        first.yScale, first.font.name.substringAfter('+'), colors.getValue(first))), text)
                }
            }

        }.getText(pdf)
        return result
    }

    fun qualify(folder: String, name: String, pages: Int, marker: Regex, gaps: List<Pair<String, String>> = emptyList(), tableFills: Boolean = false, fontFamily: String = "Liberation Sans") {
        val source = resource("/$folder/$name/source.docx")
        val reference = resource("/$folder/$name/word-reference.pdf")
        val receipt = resource("/$folder/$name/word-reference.pdf.provenance.json").decodeToString()

        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        for ((key, value) in listOf("sourceSha256" to hash(source), "pdfSha256" to hash(reference), "exporter" to "Microsoft Word ExportAsFixedFormat"))
            assertEquals(value, Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(receipt)?.groupValues?.get(1), "$name $key")
        val bytes = render(source, fontFamily)
        val target = Path.of("build/word-oracle/$folder/$name/candidate.pdf")
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
        Loader.loadPDF(bytes).use { actual -> Loader.loadPDF(reference).use { word ->
            assertEquals(pages, actual.numberOfPages, name)

            fun text(pdf: PDDocument) = PDFTextStripper().getText(pdf).replace(Regex("\\s+"), " ").trim()

            assertEquals(text(word), text(actual), "$name complete logical text")
            val expected = positions(word, marker)
            val candidate = positions(actual, marker)
            assertTrue(expected.isNotEmpty(), "$name needs Word markers")
            assertEquals(expected.keys, candidate.keys, name)
            for ((id, location) in expected) {
                val found = candidate.getValue(id)
                assertEquals(location.page, found.page, "$name $id page")
                assertEquals(location.x, found.x, .3f, "$name $id horizontal position")
                assertEquals(location.size, found.size, .05f, "$name $id font size")
                assertEquals(location.face, found.face, "$name $id font face")
                assertEquals(location.rgb, found.rgb, "$name $id color")
                // Word quantizes consecutive lines; bound cumulative drift separately from individual gaps.
                assertEquals(location.y, found.y, 1.5f, "$name $id cumulative baseline")
            }
            for ((a, b) in gaps) assertEquals(expected.getValue(b).y - expected.getValue(a).y,
                candidate.getValue(b).y - candidate.getValue(a).y, .3f, "$name $a -> $b gap")
            assertTrue(actual.pages.all { p -> p.resources.fontNames.all { p.resources.getFont(it).isEmbedded } })
            if (tableFills) verifyTableFills(source, word, actual)
            val renderer = PDFRenderer(actual)
            for (page in 0 until actual.numberOfPages) {
                val image = renderer.renderImageWithDPI(page, 96f)
                ImageIO.write(image, "png", target.resolveSibling("page-${page + 1}.png").toFile())
                val golden = requireNotNull(javaClass.getResourceAsStream("/$folder/$name/page-${page + 1}.png"))
                golden.use { assertImagesMatch(ImageIO.read(it), image, "$folder $name page ${page + 1}") {
                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                } }
            }
        } }
    }

    fun verifyTableFills(source: ByteArray, word: PDDocument, actual: PDDocument, page: Int = 0, topOffset: Float = 0f) {
        val model = OwnEngineDocxReader().read(source)
        val table = model.blocks.filterIsInstance<PdfTable>().single()
        val expected = PDFRenderer(word).renderImageWithDPI(page, 96f)
        val candidate = PDFRenderer(actual).renderImageWithDPI(page, 96f)
        // These fixtures have six 20pt lines, 6pt cell padding and a 0.5pt grid.
        for (row in 0 until 6) for (column in 0 until 4) {
            val x = ((model.page.margins.left + table.leftIndent + column * 120f + 60f) * 96 / 72).toInt()
            val y = ((model.page.margins.top + topOffset + row * 32.5f + 29f) * 96 / 72).toInt()
            assertEquals(expected.getRGB(x, y), candidate.getRGB(x, y), "Word background cell $row/$column")
        }
    }

}

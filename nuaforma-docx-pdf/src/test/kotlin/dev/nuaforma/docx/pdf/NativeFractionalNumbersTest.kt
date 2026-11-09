package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeFractionalNumbersTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `qualified fractional values match integer controls and preserve independent Word geometry`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (feature in listOf("grid", "padding", "automatic-line")) {
            val folder = "/fractional-numeric/$feature/"
            support.verify(folder + "raw/$paper/", if (feature == "grid" && paper == "a4")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard"),
                if (feature == "automatic-line") Regex("L[0-9]{2}") else Regex("G[0-9]{3}"),
                if (feature == "padding") 120 else if (feature == "grid") 16 else 30)
            val raw = support.bytes(folder + "raw/$paper/source.docx")
            val floor = support.bytes(folder + "floor/$paper/source.docx")
            Loader.loadPDF(support.generate(raw, "standard")).use { actual ->
                Loader.loadPDF(support.generate(floor, "standard")).use { control ->
                    Loader.loadPDF(support.bytes(folder + "raw/$paper/word-reference.pdf")).use { word ->
                        Loader.loadPDF(support.bytes(folder + "floor/$paper/word-reference.pdf")).use { wordFloor ->
                            Loader.loadPDF(support.bytes(folder + "ceil/$paper/word-reference.pdf")).use { wordCeil ->
                                val rawImage = PDFRenderer(word).renderImageWithDPI(0, 96f)
                                assertImagesMatch(rawImage, PDFRenderer(wordFloor).renderImageWithDPI(0, 96f)) {
                                    pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                                }
                                val ceilImage = PDFRenderer(wordCeil).renderImageWithDPI(0, 96f)
                                assertFalse(rawImage.getRGB(0, 0, rawImage.width, rawImage.height, null, 0, rawImage.width)
                                    .contentEquals(ceilImage.getRGB(0, 0, ceilImage.width, ceilImage.height, null, 0, ceilImage.width)))
                            }
                        }
                        assertEquals(1, actual.numberOfPages)
                        assertEquals(1, control.numberOfPages)
                        val strip = PDFTextStripper().apply { sortByPosition = true }
                        assertEquals(strip.getText(word).replace(Regex("\\s+"), ""), strip.getText(actual).replace(Regex("\\s+"), ""))
                        val pattern = if (feature == "automatic-line") Regex("L[0-9]{2}") else Regex("G[0-9]{3}")
                        val expected = support.positions(word, pattern)
                        val found = support.positions(actual, pattern)
                        assertEquals(if (feature == "padding") 120 else if (feature == "grid") 16 else 30, found.size)
                        assertEquals(expected.keys, found.keys)
                        for ((marker, before) in expected) {
                            assertEquals(before.x, found.getValue(marker).x, .21f, feature + marker)
                            assertEquals(before.y, found.getValue(marker).y, .21f, feature + marker)
                        }
                        assertImagesMatch(PDFRenderer(control).renderImageWithDPI(0, 96f), PDFRenderer(actual).renderImageWithDPI(0, 96f)) {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                        assertTrue(actual.getPage(0).resources.fontNames.all { actual.getPage(0).resources.getFont(it).isEmbedded })
                    }
                }
            }
        }
    }

    @Test
    fun `malformed fractional source fields fail before binding and publish no PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        for ((feature, old, element) in listOf(Triple("grid", "1000.8", "gridCol"),
            Triple("padding", "100.8", "top"), Triple("automatic-line", "252.8", "spacing"))) {
            for (value in listOf("-1.2", "288000.1", "1e3")) support.rejectChanged("/fractional-numeric/$feature/raw/a4/source.docx", "word/document.xml", element) {
                it.replaceFirst(old, value)
            }
        }
    }

}

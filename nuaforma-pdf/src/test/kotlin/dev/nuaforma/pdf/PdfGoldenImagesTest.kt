package dev.nuaforma.pdf

import dev.nuaforma.image.comparison.kotlin.ImageComparisonAssertionError
import dev.nuaforma.image.comparison.model.ImageComparisonState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class PdfGoldenImagesTest {
    @TempDir lateinit var temporary: Path

    private fun white(width: Int = 16) = BufferedImage(width, 16, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().let { graphics ->
            try { graphics.color = Color.WHITE; graphics.fillRect(0, 0, this.width, height) }
            finally { graphics.dispose() }
        }
    }

    @Test fun `identical images pass without diagnostic output`() {
        val output = temporary.resolve("match")
        PdfGoldenImages.assertMatches(white(), white(), output, "identical")
        assertFalse(Files.exists(output))
    }

    @Test fun `one changed pixel fails and preserves original and difference images`() {
        val expected = white()
        val actual = white().apply { setRGB(0, 0, Color.BLACK.rgb) }
        val output = temporary.resolve("pixel")
        val failure = assertThrows(ImageComparisonAssertionError::class.java) {
            PdfGoldenImages.assertMatches(expected, actual, output, "one pixel")
        }
        assertEquals(ImageComparisonState.MISMATCH, failure.comparisonResult.imageComparisonState)
        assertTrue(failure.comparisonResult.differencePercent > 0)
        assertEquals(1, failure.comparisonResult.rectangles.size)
        assertEquals(Color.WHITE.rgb, ImageIO.read(output.resolve("expected.png").toFile()).getRGB(0, 0))
        assertEquals(Color.BLACK.rgb, ImageIO.read(output.resolve("actual.png").toFile()).getRGB(0, 0))
        assertNotEquals(Color.BLACK.rgb, ImageIO.read(output.resolve("difference.png").toFile()).getRGB(0, 0))
        assertTrue(Files.readString(output.resolve("comparison.txt")).contains("MISMATCH"))
        assertEquals(Color.WHITE.rgb, expected.getRGB(0, 0))
        assertEquals(Color.BLACK.rgb, actual.getRGB(0, 0))
    }

    @Test fun `different dimensions fail and record both image sizes`() {
        val output = temporary.resolve("size")
        val failure = assertThrows(ImageComparisonAssertionError::class.java) {
            PdfGoldenImages.assertMatches(white(), white(17), output, "size")
        }
        assertEquals(ImageComparisonState.SIZE_MISMATCH, failure.comparisonResult.imageComparisonState)
        assertEquals(16, ImageIO.read(output.resolve("expected.png").toFile()).width)
        assertEquals(17, ImageIO.read(output.resolve("actual.png").toFile()).width)
        assertTrue(Files.exists(output.resolve("difference.png")))
        val summary = Files.readString(output.resolve("comparison.txt"))
        assertTrue(summary.contains("SIZE_MISMATCH") && summary.contains("16x16") && summary.contains("17x16"))
    }
}

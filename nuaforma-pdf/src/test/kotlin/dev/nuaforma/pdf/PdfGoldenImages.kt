package dev.nuaforma.pdf

import dev.nuaforma.image.comparison.kotlin.ImageComparisonAssertionError
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** A failed comparison records diagnostics, never a replacement baseline. */
internal object PdfGoldenImages {
    fun assertMatches(expected: BufferedImage, actual: BufferedImage, directory: Path, label: String) {
        try {
            assertImagesMatch(expected, actual, "$label; diagnostics: ${directory.toAbsolutePath()}") {
                pixelTolerance = 0.0
                allowedDifferentPixelsPercent = 0.0
                threshold = 1
            }
        } catch (failure: ImageComparisonAssertionError) {
            try {
                Files.createDirectories(directory)
                check(ImageIO.write(expected, "png", directory.resolve("expected.png").toFile()))
                check(ImageIO.write(actual, "png", directory.resolve("actual.png").toFile()))
                failure.comparisonResult.writeResultTo(directory.resolve("difference.png").toFile())
                Files.writeString(directory.resolve("comparison.txt"), requireNotNull(failure.message))
            } catch (diagnosticFailure: Exception) {
                failure.addSuppressed(diagnosticFailure)
            }
            throw failure
        }
    }
}

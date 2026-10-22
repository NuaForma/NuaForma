package dev.nuaforma.docx.pdf.internal

import dev.nuaforma.docx.pdf.LibreOfficeConfiguration
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Properties

/** Captured trusted host settings, never generation options or template values. */
internal object PdfRuntimeConfiguration {
    fun read(properties: Properties): LibreOfficeConfiguration? {
        val validation = validation(properties)
        val parallelism = properties.getProperty("nuaforma.pdf.parallelism", "1").toIntOrNull()
        require(parallelism != null && parallelism in 1..8) { "Invalid nuaforma.pdf.parallelism; expected 1 through 8" }
        val launcher = properties.getProperty("nuaforma.pdf.launcher") ?: return null
        val path = try { Path.of(launcher) }
            catch (_: InvalidPathException) { throw IllegalArgumentException("Invalid nuaforma.pdf.launcher") }
        return LibreOfficeConfiguration(path, parallelism, validation)
    }

    fun validation(properties: Properties): Boolean = when (properties.getProperty("nuaforma.pdf.pdfaValidation")) {
        null, "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Invalid nuaforma.pdf.pdfaValidation; expected true or false")
    }
}

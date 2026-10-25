package dev.nuaforma.docx.pdf

import java.nio.file.Path

/** Trusted application configuration, never a generation option or template data field. */
class LibreOfficeConfiguration(val launcher: Path, val maximumParallelConversions: Int, val pdfaValidation: Boolean) {
    @JvmOverloads
    constructor(launcher: Path, maximumParallelConversions: Int = 1) : this(launcher, maximumParallelConversions, true)
    init {
        require(launcher.isAbsolute) { "Configure an absolute path to the approved isolated LibreOffice launcher" }
        require(maximumParallelConversions in 1..8) { "Parallel conversion limit must be between one and eight" }
    }
}

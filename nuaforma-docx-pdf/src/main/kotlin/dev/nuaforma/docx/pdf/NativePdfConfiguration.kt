package dev.nuaforma.docx.pdf

import dev.nuaforma.docx.pdf.internal.PdfFontMetadata
import dev.nuaforma.docx.DeploymentFonts
import dev.nuaforma.contracts.*
import dev.nuaforma.pdf.PdfConformanceValidator
import dev.nuaforma.pdf.PdfFont
import dev.nuaforma.pdf.PdfFontEmbedding
import dev.nuaforma.pdf.PdfProcessValidator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.Semaphore

/** Explicit caller fonts; family and face must match the actual font, with no system substitution. */
class NativePdfFont @JvmOverloads constructor(
    val family: String, val face: String, bytes: ByteArray,
    val embedding: PdfFontEmbedding = PdfFontEmbedding.SUBSET,
    val origin: FontOrigin = FontOrigin.MOUNTED,
    val resourceId: String? = null,
) {
    private val content = bytes.copyOf()
    init {
        require(content.size in 1..8_388_608) { "Invalid native PDF font size" }
        val metadata = PdfFontMetadata.read(content)
        require(family in metadata.families && face == metadata.face) { "Native PDF font family or face does not match its metadata" }
        require(resourceId == null || resourceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "Invalid native font resource identifier" }
    }
    internal val byteSize: Int get() = content.size
    internal fun resource(id: String) = PdfFont(id, content, embedding)
    internal fun source() = ResourceSource { content.inputStream() }
}

/** Instance-scoped native backend. Admission is bounded and immediate, without an unbounded queue. */
class NativePdfConfiguration @JvmOverloads constructor(
    fonts: List<NativePdfFont>,
    val validator: PdfConformanceValidator? = null,
    maximumConcurrentConversions: Int = 2,
    val pdfaValidation: Boolean = true,
) {
    val fonts: List<NativePdfFont> = Collections.unmodifiableList(ArrayList(fonts))
    internal val admission: Semaphore
    init {
        require(maximumConcurrentConversions in 1..64) { "Invalid native conversion concurrency limit" }
        require(this.fonts.size <= 256 && this.fonts.sumOf { it.byteSize.toLong() } <= 134_217_728) { "Invalid native font catalog budget" }
        require(this.fonts.map { it.family.lowercase(java.util.Locale.ROOT) to it.face }.distinct().size == this.fonts.size) { "Native font family and face must be unique" }
        admission = Semaphore(maximumConcurrentConversions)
    }

    companion object {
        /** Capture trusted JVM settings once; libraries never inspect process environment variables. */
        @JvmStatic fun configured(): NativePdfConfiguration {
            val properties = System.getProperties()
            val enabled = when (properties.getProperty("nuaforma.pdf.pdfaValidation")) {
                null, "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("Invalid nuaforma.pdf.pdfaValidation; expected true or false")
            }
            val parallelism = properties.getProperty("nuaforma.pdf.parallelism", "1").toIntOrNull()
            require(parallelism != null && parallelism in 1..8) { "Invalid nuaforma.pdf.parallelism; expected 1 through 8" }
            val deployment = DeploymentFonts.configured()
            val selected = deployment.catalog.fonts.groupBy { it.family.lowercase(java.util.Locale.ROOT) to it.face }
                .values.map { faces -> faces.maxBy { if (it.origin == FontOrigin.MOUNTED) 1 else 0 } }
            val declarations = selected.filter { it.resource != null }.map { mapOf("family" to it.family, "face" to it.face, "resource" to it.resource!!) }
            val bound = deployment.bind(GenerationRequest(byteArrayOf().inputStream(), GenerationRequirements(
                FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf("fonts" to declarations)))))
            val fonts = selected.filter { it.resource != null }.map { font ->
                val bytes = bound.resources.getValue(font.resource!!).openStream().use { it.readNBytes(8_388_609) }
                val embedding = if (PdfFontMetadata.read(bytes).permissions and 256 != 0) PdfFontEmbedding.FULL else PdfFontEmbedding.SUBSET
                NativePdfFont(font.family, font.face, bytes, embedding, font.origin, font.resource)
            }
            val java = properties.getProperty("nuaforma.pdf.validator.java")
            val directory = properties.getProperty("nuaforma.pdf.validator.directory")
            val launcher = properties.getProperty("nuaforma.pdf.validator.launcher")?.let(Path::of)
            val validator = if (java != null && directory != null && Files.isRegularFile(Path.of(java)) && Files.isExecutable(Path.of(java)) &&
                Files.isDirectory(Path.of(directory)) && (launcher == null || Files.isRegularFile(launcher) && Files.isExecutable(launcher))) {
                val libraries = Files.list(Path.of(directory)).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().limit(65).toList() }
                PdfProcessValidator(Path.of(java), libraries, Path.of(properties.getProperty("java.io.tmpdir")), launcher = launcher)
            } else null
            return NativePdfConfiguration(fonts, validator, parallelism, enabled)
        }
    }
}

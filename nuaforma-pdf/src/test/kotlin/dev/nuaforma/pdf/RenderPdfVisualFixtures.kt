package dev.nuaforma.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.apache.pdfbox.pdmodel.PDDocument
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import javax.imageio.ImageIO

/** Explicit candidate generation only; never overwrites checked-in golden images. */
object RenderPdfVisualFixtures {
    const val DPI = 150f

    internal fun renderer(pdf: PDDocument) = PDFRenderer(pdf).apply { isSubsamplingAllowed = false }
    internal fun rasterize(renderer: PDFRenderer, index: Int) = renderer.renderImageWithDPI(index, DPI, ImageType.RGB)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    internal fun extractedText(pdf: PDDocument): String {
        val text = StringBuilder()
        object : PDFTextStripper() {
            override fun processTextPosition(position: TextPosition) { text.append(position.unicode) }
        }.getText(pdf)
        return PDFTextStripper().getText(pdf) + "\n" + text
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Supply a fresh output directory under this module's build directory" }
        val build = Path.of("build").toRealPath()
        val output = Path.of(args[0]).toAbsolutePath().normalize()
        require(output.startsWith(build) && output != build && !Files.exists(output)) { "Use a fresh directory under build; golden resources cannot be overwritten" }
        require(output.parent.toRealPath().startsWith(build)) { "Output parent must remain under build" }
        Files.createDirectory(output)
        require(output.toRealPath().startsWith(build)) { "Output must remain under build" }
        val manifest = mutableListOf("case\tpage\twidth\theight\tsha256")
        val fonts = FoundationLetter.fonts()
        for (case in PdfVisualFixtures.cases()) {
            val bytes = PdfEngine().render(case.document, fonts)
            Files.write(output.resolve("${case.id}.pdf"), bytes)
            Loader.loadPDF(bytes).use { pdf ->
                check(pdf.numberOfPages == case.pages) { "${case.id}: expected ${case.pages} pages, got ${pdf.numberOfPages}" }
                val text = extractedText(pdf)
                case.requiredText.forEach { check(text.contains(it)) { "${case.id}: missing required content" } }
                val renderer = renderer(pdf)
                for (index in 0 until pdf.numberOfPages) {
                    val image = rasterize(renderer, index)
                    val file = output.resolve("${case.id}-page-${index + 1}.png")
                    check(ImageIO.write(image, "png", file.toFile()))
                    manifest += "${case.id}\t${index + 1}\t${image.width}\t${image.height}\t${sha256(Files.readAllBytes(file))}"
                }
            }
            println("${case.id}: ${case.pages} pages; content checked")
        }
        Files.writeString(output.resolve("pages.tsv"), manifest.joinToString("\n", postfix = "\n"))
        val assets = listOf("/fonts/LiberationSans-Regular.ttf", "/fonts/LiberationSans-Bold.ttf", "/fonts/LiberationSans-Italic.ttf", "/fixtures/nuaforma-icon-transparent.png")
        Files.writeString(output.resolve("inputs.sha256"), assets.joinToString("\n", postfix = "\n") { "${sha256(FoundationLetter.resource(it))}  $it" })
        println("Candidate images only: visual review and explicit approval are required before installing or replacing goldens.")
    }
}

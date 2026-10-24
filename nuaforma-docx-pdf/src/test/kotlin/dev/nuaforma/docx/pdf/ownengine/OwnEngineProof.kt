package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.PdfEngine
import dev.nuaforma.pdf.PdfFont
import java.nio.file.Files
import java.nio.file.Path

/** Manual qualification entry point, never registered as a production provider. */
object OwnEngineProof {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3) { "Provide source DOCX, output PDF, and caller font directory" }
        val document = OwnEngineDocxReader().read(Files.readAllBytes(Path.of(args[0])))
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, face) ->
            PdfFont(id, Files.readAllBytes(Path.of(args[2]).resolve("LiberationSans-$face.ttf")))
        }
        val output = Path.of(args[1])
        Files.createDirectories(output.toAbsolutePath().parent)
        Files.write(output, PdfEngine().render(document, fonts))
        println("DOCX proof rendered through the neutral model; no external converter and no PDF/A claim.")
    }
}

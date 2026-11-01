package dev.nuaforma.docx.pdf.ownengine

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO

/** Explicit initial-fixture authoring tool. It cannot write or replace checked-in references. */
object WordFormattingProof {

    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 2..3) { "Provide trusted synthetic case directory, fresh build output directory and optional fixture family" }
        val input = Path.of(args[0]).toAbsolutePath().normalize()
        val output = Path.of(args[1]).toAbsolutePath().normalize()
        val family = when (args.getOrNull(2)) {
            null, "liberation-sans" -> "Liberation Sans"
            "noto-serif" -> "Noto Serif"
            else -> error("Select a qualified fixture family: liberation-sans or noto-serif")
        }
        require(output.startsWith(Path.of("build").toAbsolutePath().normalize())) { "Proof outputs belong under build" }
        Files.list(input).use { paths -> paths.filter { Files.isDirectory(it) }.sorted().forEach { folder ->
            val destination = output.resolve(folder.fileName.toString())
            check(!Files.exists(destination)) { "Use a fresh proof output directory" }
            val bytes = WordLayoutOracle.render(Files.readAllBytes(folder.resolve("source.docx")), family)
            Files.createDirectories(destination)
            Files.write(destination.resolve("candidate.pdf"), bytes, StandardOpenOption.CREATE_NEW)
            Loader.loadPDF(bytes).use { pdf ->
                val renderer = PDFRenderer(pdf)
                for (page in 0 until pdf.numberOfPages) check(ImageIO.write(renderer.renderImageWithDPI(page, 96f), "png",
                    destination.resolve("page-${page + 1}.png").toFile()))
            }
        } }
        println("Synthetic formatting candidates generated; references remain unchanged")
    }

}

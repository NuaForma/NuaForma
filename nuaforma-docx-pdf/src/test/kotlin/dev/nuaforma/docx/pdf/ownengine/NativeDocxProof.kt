package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.*
import dev.nuaforma.pdf.PdfProcessValidator
import java.nio.file.Files
import java.nio.file.Path

/** Explicit trusted-fixture qualification through the registered provider, never a shipped entry point. */
object NativeDocxProof {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 5..6) { "Provide source DOCX, output PDF, fonts, validator directory, Java 17 executable and optional synthetic variable-row count" }
        val target = Path.of(args[1]).toAbsolutePath()
        check(!Files.exists(target)) { "Use a new candidate PDF path; references must not be overwritten" }
        Files.createDirectories(target.parent)
        val scratch = Files.createTempDirectory(target.parent, "native-qualification-")
        try {
            val validator = PdfProcessValidator(Path.of(args[4]), Files.list(Path.of(args[3])).use { paths ->
                paths.filter { it.toString().endsWith(".jar") }.sorted().toList()
            }, scratch)
            val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
                NativePdfFont("Liberation Sans", face, Files.readAllBytes(Path.of(args[2]).resolve("LiberationSans-$file.ttf")))
            }
            val provider = DocxPdfProvider(NativePdfConfiguration(fonts, validator))
            val output = java.io.ByteArrayOutputStream()
            val original = Files.readAllBytes(Path.of(args[0]))
            val rows = args.getOrNull(5)?.toInt()
            val source = if (rows == null) original else NativeVariableReport.template(original)
            val data = rows?.let(NativeVariableReport::data) ?: emptyMap()
            provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard"))), data), output, GenerationContext())
            Files.write(target, output.toByteArray())
            val filled = target.resolveSibling(target.fileName.toString() + ".filled.docx")
            Files.newOutputStream(filled, java.nio.file.StandardOpenOption.CREATE_NEW).use { document ->
                provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX)), data),
                    document, GenerationContext())
            }
            check(Files.list(scratch).use { it.count() } == 0L) { "Validator left private resources" }
        } finally { Files.delete(scratch) }
        println("Registered native DOCX/PDF provider candidate generated with independent structural validation")
    }
}

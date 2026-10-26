package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.*
import dev.nuaforma.pdf.PdfProcessValidator
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Reproducible source/Word inputs and independently validated native watermark candidates. */
object NativeWatermarkProof {
    private data class Case(val id: String, val text: String, val opacity: Double, val rotation: Double, val landscape: Boolean = false, val long: Boolean = false)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 5) { "Supply source DOCX, fresh output directory, fonts, validator directory and Java 17 executable" }
        val output = Path.of(args[1]).toAbsolutePath().normalize()
        require(!Files.exists(output) && output.startsWith(Path.of("build").toAbsolutePath().normalize())) { "Use a fresh module build directory" }
        Files.createDirectory(output)
        val scratch = Files.createDirectory(output.resolve("validation-temp"))
        try {
            val validator = PdfProcessValidator(Path.of(args[4]), Files.list(Path.of(args[3])).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, scratch)
            val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
                NativePdfFont("Liberation Sans", face, Files.readAllBytes(Path.of(args[2]).resolve("LiberationSans-$file.ttf")))
            }
            val provider = DocxPdfProvider(NativePdfConfiguration(fonts, validator))
            val original = Files.readAllBytes(Path.of(args[0]))
            val cases = listOf(Case("a4-transparent", "{#if true}CONCEPT {reference}{#else}FINAL{/if}", 0.25, -45.0),
                Case("letter-landscape-opaque", "FINAL {reference}", 1.0, 30.0, landscape = true),
                Case("multipage", "CONCEPT {reference}", 0.3, -45.0, long = true),
                Case("cyrillic-greek", "Сведения και στοιχεία", 0.25, -45.0),
                Case("hebrew", "טיוטה 57", 0.3, -45.0))
            for (case in cases) {
                val source = variant(original, case)
                Files.write(output.resolve("${case.id}.input.docx"), source)
                val watermark = TextWatermark(case.text, "#127F87", case.opacity, case.rotation)
                for ((suffix, format, options) in listOf(Triple(".pdf", DocumentFormat.PDF, GenerationOptions(watermark)),
                    Triple(".docx", DocumentFormat.DOCX, GenerationOptions(watermark)), Triple(".plain.pdf", DocumentFormat.PDF, GenerationOptions()))) {
                    val bytes = ByteArrayOutputStream()
                    val outputOptions = if (format == DocumentFormat.PDF) mapOf("profile" to "pdfa-3b") else emptyMap()
                    provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                        FormatRequest(format, outputOptions), options), mapOf("reference" to "NF-057")), bytes, GenerationContext())
                    Files.write(output.resolve(case.id + suffix), bytes.toByteArray())
                }
                println("${case.id}: native archival PDF, plain control and Word-export input generated")
            }
            check(Files.list(scratch).use { it.count() } == 0L)
        } finally { Files.delete(scratch) }
    }

    private fun variant(source: ByteArray, case: Case): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { target -> ZipInputStream(source.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                var content = input.readBytes()
                if (entry.name == "word/document.xml") {
                    var xml = content.toString(Charsets.UTF_8)
                    if (case.landscape) xml = xml.replace(Regex("<w:pgSz[^>]*/>"), "<w:pgSz w:w=\"15840\" w:h=\"12240\" w:orient=\"landscape\"/>")
                    if (case.long) {
                        val extra = (1..90).joinToString("") { index ->
                            "<w:p><w:pPr><w:spacing w:after=\"80\" w:line=\"320\" w:lineRule=\"exact\"/></w:pPr><w:r><w:t>W$index Independent watermark pagination proof.</w:t></w:r></w:p>"
                        }
                        xml = xml.replace("<w:sectPr", extra + "<w:sectPr")
                    }
                    content = xml.toByteArray()
                }
                target.putNextEntry(ZipEntry(entry.name)); target.write(content); target.closeEntry()
            }
        } }
    }.toByteArray()
}

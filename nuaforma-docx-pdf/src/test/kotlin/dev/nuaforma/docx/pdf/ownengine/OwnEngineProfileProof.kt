package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Manual profile qualification; the production provider remains unchanged. */
object OwnEngineProfileProof {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 5) { "Provide case TSV, fonts, validator directory, Java 17 executable and output directory" }
        val output = Path.of(args[4]); Files.createDirectories(output)
        val scratch = output.resolve("validation-tmp"); Files.createDirectories(scratch)
        val verifier = PdfProcessValidator(Path.of(args[3]), Files.list(Path.of(args[2])).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".jar") }.sorted().toList()
        }, scratch)
        val engine = PdfEngine(verifier)
        val receipts = mutableListOf("case\tprofile\tstatus\tsha256\tbytes")
        val cases = Files.readAllLines(Path.of(args[0])).filter { it.isNotBlank() && !it.startsWith("#") }
        for (line in cases) {
            val fields = line.split('\t'); require(fields.size == 4)
            val (name, source, reject1b, embedding) = fields
            require(name.matches(Regex("[a-z0-9/-]+")) && ".." !in name)
            val model = OwnEngineDocxReader().read(Files.readAllBytes(Path.of(source)))
            val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "bolditalic" to "BoldItalic").map { (id, face) ->
                PdfFont(id, Files.readAllBytes(Path.of(args[1]).resolve("LiberationSans-$face.ttf")), PdfFontEmbedding.valueOf(embedding))
            }
            for (profile in PdfOutputProfile.entries) {
                val mustReject = profile == PdfOutputProfile.PDFA_1B && reject1b == "true"
                val target = output.resolve(name).resolve("${profile.id}.pdf")
                check(!Files.exists(target)) { "Use a fresh qualification output directory" }
                try {
                    val bytes = engine.render(model, fonts, PdfOutputOptions(profile, "NuaForma profile qualification", "NuaForma"))
                    check(!mustReject) { "Transparent source unexpectedly accepted as PDF/A-1b" }
                    Files.createDirectories(target.parent); Files.write(target, bytes)
                    val digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    receipts += "$name\t${profile.id}\tpassed\t$digest\t${bytes.size}"
                } catch (failure: PdfRenderException) {
                    if (!mustReject || failure.code != PdfFailureCode.UNSUPPORTED_OUTPUT) throw failure
                    receipts += "$name\t${profile.id}\trejected-transparency\t-\t0"
                }
            }
            println("Qualified profiles: $name ($embedding)")
        }
        check(Files.list(scratch).use { it.count() } == 0L)
        Files.write(output.resolve("validation.tsv"), receipts)
    }
}

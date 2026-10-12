package dev.nuaforma.docx.pdf.adapters.verapdf

import org.verapdf.pdfa.Foundries
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider
import org.verapdf.pdfa.flavours.PDFAFlavour
import org.verapdf.processor.reports.enums.JobEndStatus
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Child-JVM entry point only. No vendor parser or report handler runs in the hosting JVM. */
internal object VeraPdfValidator {
    @JvmStatic
    fun main(arguments: Array<String>) {
        // Dependency absence is part of the qualified runtime, not an optional fallback.
        check(runCatching { Class.forName("javanet.staxutils.IndentingXMLStreamWriter") }.exceptionOrNull() is ClassNotFoundException)
        VeraGreenfieldFoundryProvider.initialise()
        if (arguments.contentEquals(arrayOf("--version"))) {
            println("NuaForma PDF/A validator 1")
            return
        }
        require(arguments.size == 2)
        val flavour = when (arguments[1]) {
            "pdfa-1b" -> PDFAFlavour.PDFA_1_B
            "pdfa-2b" -> PDFAFlavour.PDFA_2_B
            "pdfa-3b" -> PDFAFlavour.PDFA_3_B
            else -> error("Unsupported PDF/A profile")
        }
        val path = Path.of(arguments[0])
        val before = sha256(path)
        val foundry = Foundries.defaultInstance()
        foundry.createParser(path.toFile(), flavour).use { parser ->
            foundry.createValidator(flavour, false).use { validator ->
                val result = validator.validate(parser)
                check(result.jobEndStatus == JobEndStatus.NORMAL && result.pdfaFlavour == flavour &&
                    result.totalAssertions > 0 && result.isCompliant && result.failedChecks.isEmpty()) {
                    "PDF/A conformance validation failed"
                }
            }
        }
        check(before == sha256(path)) { "PDF bytes changed during validation" }
        println("NuaForma PDF/A valid ${arguments[1]} $before")
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }
}

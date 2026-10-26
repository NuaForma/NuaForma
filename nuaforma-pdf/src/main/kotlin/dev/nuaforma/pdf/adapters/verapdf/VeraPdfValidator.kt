package dev.nuaforma.pdf.adapters.verapdf

import dev.nuaforma.pdf.PdfRenderControl
import dev.nuaforma.pdf.adapters.pdfbox.PdfStaticValidator
import org.verapdf.pdfa.Foundries
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider
import org.verapdf.pdfa.flavours.PDFAFlavour
import org.verapdf.processor.reports.enums.JobEndStatus
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Child-JVM entry point only; vendor parsers must never execute in the hosting JVM. */
internal object VeraPdfValidator {
    @JvmStatic fun main(arguments: Array<String>) {
        require(Runtime.version().feature() == 17) { "Validator requires Java 17" }
        check(runCatching { Class.forName("javanet.staxutils.IndentingXMLStreamWriter") }.exceptionOrNull() is ClassNotFoundException)
        if (arguments.contentEquals(arrayOf("--version"))) {
            VeraGreenfieldFoundryProvider.initialise()
            Foundries.defaultInstance()
            println("NuaForma PDF validator ready 17")
            return
        }
        require(arguments.size in 2..3 && (arguments.size == 2 || arguments[2] == "structure"))
        val file = Path.of(arguments[0]); val before = digest(file)
        PdfStaticValidator.validate(file, 67_108_864, PdfRenderControl(), if (arguments[1] == "pdfa-1b") "1.4" else "1.7")
        if (arguments.size == 3) {
            check(before == digest(file)) { "PDF changed during validation" }
            println("NuaForma PDF structure valid ${arguments[1]} $before")
            return
        }
        if (arguments[1] == "standard") {
            check(before == digest(file)) { "PDF changed during validation" }
            println("NuaForma PDF valid standard $before")
            return
        }
        val flavour = when (arguments[1]) {
            "pdfa-1b" -> PDFAFlavour.PDFA_1_B
            "pdfa-2b" -> PDFAFlavour.PDFA_2_B
            "pdfa-3b" -> PDFAFlavour.PDFA_3_B
            else -> error("Unsupported PDF/A profile")
        }
        VeraGreenfieldFoundryProvider.initialise()
        val foundry = Foundries.defaultInstance()
        foundry.createParser(file.toFile(), flavour).use { parser ->
            foundry.createValidator(flavour, false).use { validator ->
                val result = validator.validate(parser)
                check(result.jobEndStatus == JobEndStatus.NORMAL && result.pdfaFlavour == flavour &&
                    result.totalAssertions > 0 && result.isCompliant && result.failedChecks.isEmpty()) { "PDF/A conformance validation failed" }
            }
        }
        check(before == digest(file)) { "PDF changed during validation" }
        println("NuaForma PDF/A valid ${arguments[1]} $before")
    }
    private fun digest(file: Path): String {
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input -> val buffer = ByteArray(8192); while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) } }
        return java.util.HexFormat.of().formatHex(hash.digest())
    }
}

package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionCopy
import dev.nuaforma.docx.pdf.internal.PdfOptions
import java.nio.file.Files
import java.nio.file.Path

/** Explicit local synthetic-fixture runner; no REST service, test framework or production entry point. */
object RuntimeQualification {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(Runtime.version().feature() == 17)
        require(arguments.size in 2..3)
        val source = Path.of(arguments[0])
        val output = Path.of(arguments[1])
        require(!Files.exists(output))
        val options = mapOf("paragraphDefaults" to mapOf("afterTwips" to 160, "line240ths" to 278))
        if (arguments.size == 3 && arguments[2] == "copy-matrix") {
            Files.createDirectory(output)
            val fixtures = linkedMapOf(
                "roundtrip-before" to "docx-reference/before", "roundtrip-after" to "docx-reference/after",
                "complex-expected" to "docx-generation-reference/complex-expected", "complex-generated" to "docx-generation-reference/complex-generated",
                "barcode-expected" to "font-reference/expected", "barcode-generated" to "font-reference/generated",
                "barcode-embedded" to "font-reference/embedded", "barcode-isolated" to "font-reference/isolated-embedded",
            ) + listOf("plain", "concept", "dynamic", "vertical", "transparent", "opaque").associate { "watermark-$it" to "watermark-reference/$it" }
            for ((name, relative) in fixtures) {
                val result = ConversionCopy.prepare(source.resolve("$relative.docx"), output.resolve("$name.docx"), PdfOptions.read(options), GenerationContext())
                println("Qualified copy $name: ${result.normalizedShapes} normalized shapes")
            }
            return
        }
        if (arguments.size == 3 && arguments[2] == "copy") {
            ConversionCopy.prepare(source, output, PdfOptions.read(options), GenerationContext())
            return
        }
        val fonts = Path.of("/fixture-fonts")
        val declarations = listOf(
            mapOf("family" to "Arial", "face" to "regular", "resource" to "arial"),
            mapOf("family" to "Arial", "face" to "bold", "resource" to "arialbd"),
            mapOf("family" to "Arial", "face" to "italic", "resource" to "ariali"),
            mapOf("family" to "Arial", "face" to "boldItalic", "resource" to "arialbi"),
            mapOf("family" to "Libre Barcode 39", "face" to "regular", "resource" to "barcode"),
        )
        val resources = declarations.associate { declaration ->
            val id = declaration.getValue("resource")
            id to ResourceSource { Files.newInputStream(fonts.resolve(if (id == "barcode") "LibreBarcode39-Regular.ttf" else "$id.ttf")) }
        }
        val request = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF,
            options + mapOf("fontMode" to "validate", "fonts" to declarations)))
        val provider = LegacyDocxPdfProvider(LibreOfficeConfiguration(Path.of("/opt/nuaforma/bin/render-pdf")))
        Files.newInputStream(source).use { input -> Files.newOutputStream(output).use { destination ->
            provider.generate(GenerationRequest(input, request,
                mapOf("customer" to mapOf("name" to "Ada"), "name" to "Ada", "reference" to "NF001"), resources), destination, GenerationContext())
        } }
        println("Qualified embedded generation completed")
    }
}

package dev.nuaforma.examples

import dev.nuaforma.contracts.*
import dev.nuaforma.core.ProviderRegistry
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

fun main() {
    val parts = mapOf(
        "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
        "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
        "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Hello {trim(customer.name)}</w:t></w:r></w:p><w:sectPr/></w:body></w:document>""",
    )
    val source = ByteArrayOutputStream()
    ZipOutputStream(source).use { zip -> parts.forEach { (name, value) ->
        zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
    } }
    val registry = ProviderRegistry.discover()
    check(registry.diagnostics.isEmpty())
    source.toByteArray().inputStream().use { template -> ByteArrayOutputStream().use { output ->
        registry.generate(GenerationRequest(template, GenerationRequirements(
            FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX)),
            mapOf("customer" to mapOf("name" to " Ada "))), output, GenerationContext(), "nuaforma-docx")
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            var found = false
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val text = zip.readBytes().decodeToString()
                    found = "Ada" in text && "{trim" !in text
                }
                entry = zip.nextEntry
            }
            check(found)
        }
    } }
    println("Independent Gradle Kotlin DOCX example passed")
}

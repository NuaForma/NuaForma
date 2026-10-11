package dev.nuaforma

import dev.nuaforma.contracts.*
import dev.nuaforma.core.ProviderRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class DocxEngineTest {
    private fun template(): ByteArray {
        val parts = mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hello {name}</w:t></w:r></w:p><w:sectPr/></w:body></w:document>""",
        )
        return ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip -> parts.forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
        } } }.toByteArray()
    }

    @Test
    fun `core discovers actual DOCX provider and concurrently generates isolated documents`() {
        val registry = ProviderRegistry.discover()
        assertTrue(registry.diagnostics.isEmpty(), registry.diagnostics.joinToString { it.message })
        assertEquals("nuaforma-docx", registry.providers.single().metadata.id)
        val bytes = template()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((0..7).map { index -> Callable {
                val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX),
                    GenerationOptions(watermark = if (index % 2 == 0) TextWatermark("DRAFT$index") else null))
                val output = ByteArrayOutputStream()
                val result = registry.generate(GenerationRequest(bytes.inputStream(), requirements, mapOf("name" to "Tenant$index")), output, GenerationContext())
                assertEquals(DocumentFormat.DOCX, result.output.format)
                var document = ""
                var headers = ""
                ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "word/document.xml") document = zip.readBytes().decodeToString()
                        else if (entry.name.startsWith("word/nf-header-")) headers += zip.readBytes().decodeToString()
                        entry = zip.nextEntry
                    }
                }
                assertTrue(document.contains("Tenant$index")); assertFalse(document.contains("{name}"))
                (0..7).filter { it != index }.forEach { assertFalse(document.contains("Tenant$it")) }
                if (index % 2 == 0) assertTrue(headers.contains("DRAFT$index")) else assertEquals("", headers)
                (0..7).filter { it != index }.forEach { assertFalse(headers.contains("DRAFT$it")) }
                document
            } }).map { it.get() }
            assertEquals(8, results.distinct().size)
        } finally { pool.shutdownNow() }
    }
}

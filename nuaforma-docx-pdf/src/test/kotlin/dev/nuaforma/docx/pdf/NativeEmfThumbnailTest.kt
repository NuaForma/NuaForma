package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeEmfThumbnailTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `opaque EMF previews preserve actual Word print content and existing native golden pixels`(paper: String) {
        NativeWordFeatureSupport(temporary).verify("/emf-thumbnail/$paper/", if (paper == "a4")
            listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard"),
            Regex("(?!)"), 0, golden = "/preferred-table-widths/$paper/page-1.png")
    }

    @Test
    fun `external and wrong-type previews reject without partial PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = support.bytes("/emf-thumbnail/a4/source.docx")
        for ((part, before, after) in listOf(
            Triple("_rels/.rels", "Target=\"docProps/thumbnail.emf\"", "Target=\"https://example.invalid/thumbnail.emf\" TargetMode=\"External\""),
            Triple("[Content_Types].xml", "image/x-emf", "image/jpeg"))) {
            val parts = PdfFixture.unzip(source).toMutableMap()
            val original = parts.getValue(part).decodeToString()
            assertTrue(original.contains(before))
            parts[part] = original.replace(before, after).toByteArray()
            val output = ByteArrayOutputStream()
            val failure = assertThrows(GenerationException::class.java) { support.generate(PdfFixture.zip(parts), "standard", output) }
            assertEquals(0, output.size())
            assertEquals(part, failure.diagnostics.single().part)
        }
    }

    @Test
    fun `orphan EMF preview fails source inspection and cleanup removes only the preview and its declaration`() {
        val support = NativeWordFeatureSupport(temporary)
        val parts = PdfFixture.unzip(support.bytes("/emf-thumbnail/a4/source.docx")).toMutableMap()
        val relationships = parts.getValue("_rels/.rels").decodeToString()
        val orphan = relationships.replace(Regex("<Relationship[^>]+Target=\"docProps/thumbnail\\.emf\"[^>]*/>"), "")
        assertNotEquals(relationships, orphan)
        parts["_rels/.rels"] = orphan.toByteArray()
        val source = PdfFixture.zip(parts)
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        val failure = assertThrows(dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReadException::class.java) {
            dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader(context) { _, _, _ -> "fixture-font" }
                .read(source, inspectImages = true)
        }
        assertEquals("docProps/thumbnail.emf", failure.part)
        val cleaned = ByteArrayOutputStream()
        val report = support.provider.cleanTemplate(source.inputStream(), cleaned, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        assertTrue(report.changes.any { it.part == "docProps/thumbnail.emf" })
        val actual = PdfFixture.unzip(cleaned.toByteArray())
        assertFalse(actual.containsKey("docProps/thumbnail.emf"))
        for ((part, bytes) in parts) if (part !in setOf("docProps/thumbnail.emf", "[Content_Types].xml"))
            assertArrayEquals(bytes, actual[part], part)
        assertTrue(support.generate(cleaned.toByteArray(), "standard").isNotEmpty())
    }

    @Test
    fun `supported EMF preview bytes remain in template cleanup and ordinary DOCX output`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = support.bytes("/emf-thumbnail/a4/source.docx")
        val cleaned = ByteArrayOutputStream()
        assertTrue(support.provider.cleanTemplate(source.inputStream(), cleaned, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
        assertArrayEquals(source, cleaned.toByteArray())
        val output = ByteArrayOutputStream()
        support.provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.DOCX))), output, GenerationContext())
        assertArrayEquals(PdfFixture.unzip(source).getValue("docProps/thumbnail.emf"), PdfFixture.unzip(output.toByteArray()).getValue("docProps/thumbnail.emf"))
    }

}

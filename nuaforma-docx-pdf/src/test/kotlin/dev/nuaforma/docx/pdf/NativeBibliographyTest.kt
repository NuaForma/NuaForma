package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReadException
import dev.nuaforma.docx.pdf.adapters.nativeengine.WordBibliographyMetadata
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.pdf.PdfOutputProfile
import dev.nuaforma.pdf.PdfProcessValidator
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

class NativeBibliographyTest {
    @TempDir lateinit var temporary: Path
    private val bib = "http://schemas.openxmlformats.org/officeDocument/2006/bibliography"
    private val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun provider(): DocxPdfProvider {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
            NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
        }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        return DocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }
    private fun source(bibliography: Boolean, populated: Boolean = true): MutableMap<String, ByteArray> {
        val parts = PdfFixture.unzip(resource("/own-engine/source.docx")).toMutableMap()
        val document = parts.getValue("word/document.xml").decodeToString()
        val section = Regex("<w:sectPr[\\s\\S]*?</w:sectPr>").find(document)!!.value
        val body = (1..100).joinToString("") { "<w:p><w:r><w:t>Row $it for {name}</w:t></w:r></w:p>" }
        parts["word/document.xml"] = document.replace(Regex("<w:body>[\\s\\S]*</w:body>"), "<w:body>$body$section</w:body>").toByteArray()
        if (!bibliography) return parts
        val content = if (populated) "<b:Source><b:Tag>REFERENCE54</b:Tag><b:SourceType>Book</b:SourceType><b:Title>PRIVATE_BIB {NOT_A_TEMPLATE שלום</b:Title><b:URL>file:///NEVER_FETCHED</b:URL></b:Source>" else ""
        parts["customXml/item1.xml"] = "<b:Sources xmlns:b=\"$bib\" SelectedStyle=\"/APA.XSL\" StyleName=\"APA\" Version=\"6\">$content</b:Sources>".toByteArray()
        parts["customXml/itemProps1.xml"] = "<ds:datastoreItem xmlns:ds=\"http://schemas.openxmlformats.org/officeDocument/2006/customXml\" ds:itemID=\"{A1E83116-3091-4B2E-94EA-CE8B1E3BF905}\"><ds:schemaRefs><ds:schemaRef ds:uri=\"$bib\"/></ds:schemaRefs></ds:datastoreItem>".toByteArray()
        parts["customXml/_rels/item1.xml.rels"] = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"props\" Type=\"$rel/customXmlProps\" Target=\"itemProps1.xml\"/></Relationships>".toByteArray()
        parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>", "<Relationship Id=\"bibliography54\" Type=\"$rel/customXml\" Target=\"../customXml/item1.xml\"/></Relationships>").toByteArray()
        parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString().replace("</Types>", "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/customXml/itemProps1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.customXmlProperties+xml\"/></Types>").toByteArray()
        return parts
    }
    private fun generate(parts: Map<String, ByteArray>, profile: PdfOutputProfile, provider: DocxPdfProvider): ByteArray {
        val output = ByteArrayOutputStream()
        try {
            provider.generate(GenerationRequest(PdfFixture.zip(parts).inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, options = mapOf("profile" to profile.id))), mapOf("name" to "Visible tenant")), output, GenerationContext())
        } catch (failure: GenerationException) { throw AssertionError(failure.diagnostics.joinToString { "${it.code} ${it.rejectionReason} ${it.part}: ${it.message}" }, failure) }
        return output.toByteArray()
    }

    @Test fun `passive bibliography leaves every page pixel and content unchanged in all profiles`() {
        val provider = provider()
        for (profile in PdfOutputProfile.entries) {
            val baseline = generate(source(false), profile, provider)
            for (populated in listOf(false, true)) {
                val result = generate(source(true, populated), profile, provider)
                Loader.loadPDF(baseline).use { expected -> Loader.loadPDF(result).use { actual ->
                    assertTrue(actual.numberOfPages >= 2)
                    assertEquals(expected.numberOfPages, actual.numberOfPages)
                    assertEquals(PDFTextStripper().getText(expected), PDFTextStripper().getText(actual))
                    assertFalse(PDFTextStripper().getText(actual).contains("PRIVATE_BIB"))
                    for (page in 0 until actual.numberOfPages) {
                        assertEquals(expected.getPage(page).mediaBox.lowerLeftX, actual.getPage(page).mediaBox.lowerLeftX)
                        assertEquals(expected.getPage(page).mediaBox.lowerLeftY, actual.getPage(page).mediaBox.lowerLeftY)
                        assertEquals(expected.getPage(page).mediaBox.width, actual.getPage(page).mediaBox.width)
                        assertEquals(expected.getPage(page).mediaBox.height, actual.getPage(page).mediaBox.height)
                        assertTrue(actual.getPage(page).resources.fontNames.all { actual.getPage(page).resources.getFont(it).isEmbedded })
                        assertImagesMatch(PDFRenderer(expected).renderImageWithDPI(page, 96f), PDFRenderer(actual).renderImageWithDPI(page, 96f), "Bibliography ${profile.id} page ${page + 1}") {
                            pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
                        }
                    }
                } }
            }
        }
        assertEquals(0, Files.list(temporary).use { it.count() })
    }

    private fun unsafeVariants() = listOf<(MutableMap<String, ByteArray>) -> Unit>(
            { p -> p["customXml/item1.xml"] = "<secret xmlns=\"urn:NOT_BIBLIOGRAPHY\">PRIVATE_BIB</secret>".toByteArray() },
            { p -> p["customXml/itemProps1.xml"] = p.getValue("customXml/itemProps1.xml").decodeToString().replace(bib, "https://NEVER_FETCHED").toByteArray() },
            { p -> p["customXml/_rels/item1.xml.rels"] = p.getValue("customXml/_rels/item1.xml.rels").decodeToString().replace("Target=\"itemProps1.xml\"", "Target=\"https://NEVER_FETCHED\" TargetMode=\"External\"").toByteArray() },
            { p -> p.remove("customXml/_rels/item1.xml.rels") },
            { p -> p["customXml/item2.xml"] = p.getValue("customXml/item1.xml") },
            { p ->
                val original = p.getValue("word/_rels/document.xml.rels").decodeToString()
                val existing = Regex("Id=\"([^\"]+)\"").find(original)!!.groupValues[1]
                p["word/_rels/document.xml.rels"] = original.replace("Id=\"bibliography54\"", "Id=\"$existing\"").toByteArray()
            }
        )

    @Test fun `invalid bibliography conversion graphs are rejected before entering the print model`() {
        for ((variant, change) in unsafeVariants().withIndex()) {
            val parts = source(true); change(parts)
            val context = GenerationContext()
            val xml = ConversionXml(context)
            val parsed = parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.mapValues { xml.read(it.value) }
            assertThrows(NativeDocxReadException::class.java, { WordBibliographyMetadata(context).removeFromPrintModel(parsed) }, "Unsafe conversion graph $variant")
        }
    }

    @Test fun `unqualified custom XML relationships and properties never publish PDF bytes`() {
        val provider = provider()
        // The owning DOCX round-trip removes unreachable ZIP items before this adapter sees them.
        // Their conversion-copy rejection remains independently covered above.
        for ((variant, change) in unsafeVariants().withIndex().filter { it.index != 4 }) {
            val parts = source(true); change(parts)
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java, {
                provider.generate(GenerationRequest(PdfFixture.zip(parts).inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                    FormatRequest(DocumentFormat.PDF, options = mapOf("profile" to "standard"))), mapOf("name" to "Visible tenant")), output, GenerationContext())
            }, "Unsafe bibliography variant $variant")
            assertEquals(0, output.size())
            assertFalse(error.diagnostics.toString().contains("PRIVATE_BIB"))
        }
    }
}

package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.PdfFonts
import dev.nuaforma.docx.pdf.internal.ConversionFiles
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import dev.nuaforma.docx.DocxProvider

class PdfFontsTest {
    private fun inspect(parts: Map<String, ByteArray>) = ConversionFiles.create(GenerationContext()).use { files ->
        Files.write(files.docx, PdfFixture.zip(parts))
        PdfFonts.required(files, GenerationContext())
    }

    private fun fragment(xml: String) = PdfFixture.parts().apply {
        this["word/document.xml"] = getValue("word/document.xml").decodeToString().replace("<w:body>", "<w:body>$xml").toByteArray()
    }

    private fun rejected(parts: Map<String, ByteArray>, reason: RejectionReason) {
        val diagnostic = assertThrows(GenerationException::class.java) { inspect(parts) }.diagnostics.single()
        assertSame(reason, diagnostic.rejectionReason)
        assertEquals(reason.message, diagnostic.message)
        assertFalse(diagnostic.message.contains("PRIVATE_TOKEN"))
    }

    @Test fun `font-bearing XML preserves explicit families and classifies invalid declarations safely`() {
        val drawing = "http://schemas.openxmlformats.org/drawingml/2006/main"
        val normal = fragment("""<w:p><w:r><w:sym w:font="Synthetic Symbol"/></w:r></w:p><a:latin xmlns:a="$drawing" typeface="Synthetic Drawing"/><v:shape><v:textpath string="label" style="font-family:Synthetic Path"/></v:shape>""")
        assertEquals(setOf("DejaVu Sans", "Synthetic Symbol", "Synthetic Drawing", "Synthetic Path"), inspect(normal).first)
        assertEquals(setOf("DejaVu Sans"), inspect(fragment("""<a:latin xmlns:a="$drawing" typeface=""/>""")).first)
        val cases = listOf(
            fragment("<w:sym/>") to RejectionReason.SYMBOL_FONT_IS_MISSING,
            fragment("""<a:latin xmlns:a="$drawing"/>""") to RejectionReason.DRAWING_FONT_IS_MISSING,
            fragment("""<a:latin xmlns:a="$drawing" typeface="+mj-lt"/>""") to RejectionReason.THEME_DRAWING_FONTS_ARE_NOT_SUPPORTED_BY_THIS_PDF,
            fragment("""<v:shape><v:textpath string="PRIVATE_TOKEN"/></v:shape>""") to RejectionReason.A_VML_TEXT_PATH_REQUIRES_AN_EXPLICIT_FONT_FAMILY,
            fragment("""<v:shape><v:textpath string="PRIVATE_TOKEN" style="color:black"/></v:shape>""") to RejectionReason.A_VML_TEXT_PATH_REQUIRES_AN_EXPLICIT_FONT_FAMILY,
            fragment("""<w:rFonts w:ascii=" "/>""") to RejectionReason.INVALID_OR_EXCESSIVE_PDF_FONT_DECLARATIONS,
            fragment("""<w:rFonts w:ascii="PRIVATE_TOKEN&#10;"/>""") to RejectionReason.INVALID_OR_EXCESSIVE_PDF_FONT_DECLARATIONS,
            fragment("""<w:rFonts w:ascii="${"X".repeat(129)}"/>""") to RejectionReason.INVALID_OR_EXCESSIVE_PDF_FONT_DECLARATIONS,
            fragment((1..129).joinToString("") { """<w:rFonts w:ascii="Family$it"/>""" }) to RejectionReason.INVALID_OR_EXCESSIVE_PDF_FONT_DECLARATIONS,
        )
        cases.forEach { (parts, reason) -> rejected(parts, reason) }
        for (attribute in listOf("ascii", "hAnsi")) {
            rejected(PdfFixture.parts().apply { this["word/styles.xml"] = getValue("word/styles.xml").decodeToString()
                .replace("w:$attribute=\"DejaVu Sans\"", "").toByteArray() }, RejectionReason.PDF_FONT_PRESERVATION_REQUIRES_EXPLICIT_ASCII_AND_HANSI_DOCUMENT)
        }
        rejected(PdfFixture.parts().apply { this["word/styles.xml"] = getValue("word/styles.xml").decodeToString()
            .replace("w:ascii=", "w:asciiTheme=").toByteArray() }, RejectionReason.PDF_FONT_RESOLUTION_CURRENTLY_REQUIRES_EXPLICIT_FONT_FAMILIES_INSTEAD)
    }

    @Test fun `real supplied and embedded fonts retain specific mismatch and package rejection reasons`() {
        val family = "Libre Barcode 39"
        val font = javaClass.getResourceAsStream("/fonts/LibreBarcode39-Regular.ttf")!!.use { it.readBytes() }
        val declarations = listOf(mapOf("family" to family, "resource" to "font"))
        fun request(familyName: String = family, face: String = "regular") = GenerationRequest(byteArrayOf().inputStream(),
            GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF,
                mapOf("fonts" to listOf(mapOf("family" to familyName, "face" to face, "resource" to "font"))))),
            emptyMap<String, Any?>(), mapOf("font" to ResourceSource { font.inputStream() }))
        ConversionFiles.create(GenerationContext()).use { files ->
            assertEquals(setOf(family), PdfFonts.supplied(request(), files, GenerationContext()))
            for (bad in listOf(request("PRIVATE_TOKEN"), request(face = "bold"))) {
                assertSame(RejectionReason.PDF_FONT_FAMILY_OR_FACE_DOES_NOT_MATCH_ITS,
                    assertThrows(GenerationException::class.java) { PdfFonts.supplied(bad, files, GenerationContext()) }.diagnostics.single().rejectionReason)
            }
        }
        val parts = PdfFixture.parts().apply { this["word/styles.xml"] = getValue("word/styles.xml").decodeToString().replace("DejaVu Sans", family).toByteArray() }
        val output = ByteArrayOutputStream()
        DocxProvider().generate(GenerationRequest(PdfFixture.zip(parts).inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.DOCX, mapOf("fontMode" to "embed", "fonts" to declarations))), mapOf("name" to "Ada"),
            mapOf("font" to ResourceSource { font.inputStream() })), output, GenerationContext())
        val embedded = PdfFixture.unzip(output.toByteArray())
        assertEquals(setOf(family), inspect(embedded).second)
        val table = embedded.getValue("word/fontTable.xml").decodeToString()
        val relationshipName = "word/_rels/fontTable.xml.rels"
        val relationships = embedded.getValue(relationshipName).decodeToString()
        fun tableChange(text: String) = embedded + ("word/fontTable.xml" to text.toByteArray())
        rejected(tableChange(table.replace(Regex("w:fontKey=\"[^\"]+\""), "")), RejectionReason.EMBEDDED_FONT_KEY_IS_MISSING)
        rejected(tableChange(table.replace(Regex("w:fontKey=\"[^\"]+\""), "w:fontKey=\"PRIVATE_TOKEN\"")), RejectionReason.EMBEDDED_FONT_KEY_IS_INVALID)
        rejected(tableChange(table.replace("embedRegular", "embedBold")), RejectionReason.EMBEDDED_FONT_FACE_DOES_NOT_MATCH_ITS_METADATA)
        rejected(tableChange(table.replace(family, "PRIVATE_TOKEN")), RejectionReason.EMBEDDED_FONT_FAMILY_DOES_NOT_MATCH_ITS_METADATA)
        rejected(tableChange(table.replace(Regex("w:name=\"[^\"]+\""), "")), RejectionReason.EMBEDDED_FONT_FAMILY_IS_MISSING)
        rejected(embedded - relationshipName, RejectionReason.EMBEDDED_FONT_RELATIONSHIP_IS_MISSING)
        rejected(embedded + (relationshipName to relationships.replace(Regex("Id=\"[^\"]+\""), "").toByteArray()), RejectionReason.EMBEDDED_FONT_RELATIONSHIP_HAS_NO_ID)
        rejected(embedded + (relationshipName to relationships.replace(Regex("Target=\"[^\"]+\""), "").toByteArray()), RejectionReason.EMBEDDED_FONT_RELATIONSHIP_HAS_NO_TARGET)
        rejected(embedded + (relationshipName to relationships.replace(Regex("Target=\"[^\"]+\""), "Target=\"../PRIVATE_TOKEN\"").toByteArray()), RejectionReason.EMBEDDED_FONTS_MUST_REMAIN_INSIDE_THE_FONT_PACKAGE_DIRECTORY)
        val fontPart = embedded.keys.single { it.endsWith(".odttf") }
        rejected(embedded - fontPart, RejectionReason.EMBEDDED_FONT_PART_IS_MISSING)
        rejected(embedded + (fontPart to byteArrayOf(0)), RejectionReason.EMBEDDED_FONT_KEY_IS_INVALID)
    }

    @Test fun `missing template fonts retain a specific structured reason without copying font names`() {
        ConversionFiles.create(GenerationContext()).use { files ->
            val parts = PdfFixture.parts().mapValues { (_, bytes) -> bytes.decodeToString().replace("DejaVu Sans", "PRIVATE_TOKEN Font").toByteArray() }
            Files.write(files.docx, PdfFixture.zip(parts))
            val required = PdfFonts.required(files, GenerationContext()).first
            assertTrue("PRIVATE_TOKEN Font" in required)
            val diagnostic = assertThrows(GenerationException::class.java) {
                PdfFonts.ensureAvailable(required, emptySet())
            }.diagnostics.single()
            assertEquals(DiagnosticCodes.INVALID_REQUEST, diagnostic.code)
            assertEquals(RejectionReason.A_SELECTED_DOCUMENT_FONT_IS_UNAVAILABLE_SUPPLY_IT_AS, diagnostic.rejectionReason)
            assertFalse(diagnostic.message.contains("PRIVATE_TOKEN"))
            assertTrue(diagnostic.rejectionReason!!.remediation.contains("font resources"))
        }
    }

    @Test fun `unfilled VML shape type does not invent a watermark font requirement`() {
        val path = """<v:shapetype><v:textpath on="t" fitshape="t"/></v:shapetype><v:shape><v:textpath string="CONCEPT — Ada" style='font-family:"DejaVu Sans";font-size:1pt'/></v:shape>"""
        val parts = PdfFixture.parts().apply {
            this["word/document.xml"] = getValue("word/document.xml").decodeToString().replace("<w:body>", "<w:body>$path").toByteArray()
        }
        ConversionFiles.create(GenerationContext()).use { files ->
            Files.write(files.docx, PdfFixture.zip(parts))
            assertEquals(setOf("DejaVu Sans"), PdfFonts.required(files, GenerationContext()).first)
            parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
                .replace("style='font-family:\"DejaVu Sans\";font-size:1pt'", "").toByteArray()
            Files.write(files.docx, PdfFixture.zip(parts))
            assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, assertThrows(GenerationException::class.java) {
                PdfFonts.required(files, GenerationContext())
            }.diagnostics.single().code)
        }
    }
}

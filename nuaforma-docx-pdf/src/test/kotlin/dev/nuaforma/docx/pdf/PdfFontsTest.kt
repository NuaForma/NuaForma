package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.PdfFonts
import dev.nuaforma.docx.pdf.internal.ConversionFiles
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class PdfFontsTest {
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

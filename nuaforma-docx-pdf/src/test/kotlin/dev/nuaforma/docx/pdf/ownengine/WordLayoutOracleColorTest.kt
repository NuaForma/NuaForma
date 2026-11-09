package dev.nuaforma.docx.pdf.ownengine

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WordLayoutOracleColorTest {

    @Test
    fun `marker colors follow the actual PDF color operators`() {
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                content.newLineAtOffset(72f, 700f)
                content.setNonStrokingColor(java.awt.Color(0x12, 0x34, 0x56))
                content.showText("A01 first color")
                content.newLineAtOffset(0f, -20f)
                content.setNonStrokingColor(java.awt.Color(0xAB, 0xCD, 0xEF))
                content.showText("A02 second color")
                content.endText()
            }
            val positions = WordLayoutOracle.positions(document, Regex("(A0[12])\\b"))
            assertEquals(0x123456, positions.getValue("A01").rgb)
            assertEquals(0xABCDEF, positions.getValue("A02").rgb)
        }
    }
}

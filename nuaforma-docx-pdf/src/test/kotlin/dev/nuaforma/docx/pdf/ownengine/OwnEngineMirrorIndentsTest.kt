package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OwnEngineMirrorIndentsTest {
    private val cases = listOf("a4-flip", "a4-fixed", "letter-flip", "legacy-flip", "legacy-fixed")

    private fun read(name: String) = OwnEngineDocxReader().read(WordLayoutOracle.resource("/mirror-indents/$name/source.docx"))

    @Test fun `paragraph inheritance direct disabling and hanging geometry survive section restarts`() {
        for (name in cases) {
            val model = read(name)
            val all = model.pageSequences.flatMap { it.blocks }.filterIsInstance<PdfParagraph>().associateBy {
                it.runs.filterIsInstance<PdfTextRun>().joinToString("") { r -> r.text }.substringBefore(' ')
            }
            assertEquals(PdfParagraphPageOffset(-18f, if (name == "legacy-flip") PdfPageSide.ODD else PdfPageSide.EVEN), all.getValue("M10").pageOffset)
            assertEquals(-36f, all.getValue("F10").pageOffset?.x)
            assertNull(all.getValue("N20").pageOffset)
            assertEquals(2, model.pageSequences.size)
            assertEquals(2, model.pageSequences.last().numbering.start)
        }
    }

    @Test fun `five real Word exports qualify odd even physical pages including numbering restart`() {
        for (name in cases) WordLayoutOracle.qualify("mirror-indents", name, 4, Regex("^([MNRF][1-4]0)\\b"))
    }

}

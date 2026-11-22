package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfLink
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTextRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeExternalHyperlinkFragmentTest {
    private fun read(address: String, fragment: String): PdfTextRun {
        val parts = PdfFixture.parts()
        val relationships = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val relPart = "word/_rels/document.xml.rels"
        val link = """<Relationship Id="weblink" Type="$relationships/hyperlink" Target="$address" TargetMode="External"/>"""
        parts[relPart] = parts.getValue(relPart).decodeToString().replace("</Relationships>", "$link</Relationships>").toByteArray()
        val documentPart = "word/document.xml"
        val run = "<w:r><w:rPr><w:b/></w:rPr><w:t>Hello {name}</w:t></w:r>"
        val markup = """<w:hyperlink xmlns:r="$relationships" r:id="weblink" w:anchor="$fragment">$run</w:hyperlink>"""
        parts[documentPart] = parts.getValue(documentPart).decodeToString().replace(run, markup).toByteArray()
        val document = NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(PdfFixture.zip(parts))
        return (document.blocks.single() as PdfParagraph).runs.filterIsInstance<PdfTextRun>().single()
    }

    @Test
    fun wordExternalFragmentsRemainPartOfTheExternalDestination() {
        val address = "https://example.test/report?x=1"
        for ((fragment, suffix) in listOf("chapter" to "#chapter", "a%20b" to "#a%20b", "café noir" to "#caf%C3%A9%20noir",
            "a#b" to "#a%23b", "a/b?c=d&amp;e" to "#a/b?c=d&e", "" to "")) {
            val label = read(address, fragment)
            assertEquals("Hello {name}", label.text)
            assertEquals(PdfLink.External(address + suffix), label.navigation?.link)
        }
        assertEquals(PdfLink.External("$address#saved"), read("$address#saved", "other").navigation?.link)
        assertEquals(PdfLink.External("mailto:example@example.test#chapter"),
            read("mailto:example@example.test", "chapter").navigation?.link)
    }

    @Test
    fun malformedOrUnboundedFragmentsKeepTheExistingUriRestrictions() {
        val address = "https://example.test/report"
        for ((base, fragment) in listOf(address to "&#xA;", address to "a".repeat(2_050),
            "file:///local" to "chapter", "https://user:pass@example.test/" to "chapter")) {
            assertThrows(NativeDocxReadException::class.java) { read(base, fragment) }
        }
    }
}

package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.PdfBoxAnchor
import dev.nuaforma.pdf.PdfParagraph
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeVerticalPictureAlignmentTest {
    private fun source() = requireNotNull(javaClass.getResourceAsStream("/vertical-picture-alignment/a4-center.docx"))
        .use { PdfFixture.unzip(it.readBytes()) }

    private fun read(rewrite: (String) -> String) = NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }
        .read(PdfFixture.zip(source().mapValues { (name, data) ->
            if (name == "word/document.xml") rewrite(data.decodeToString()).toByteArray() else data
        }))

    @Test
    fun `vertical alignment uses the page and the outer image extent including effects`() {
        val pageHeight = 841.9f
        val outerHeight = 96f
        val expected = mapOf("top" to 0f, "center" to (pageHeight - outerHeight - .75f) / 2f,
            "bottom" to pageHeight - outerHeight - .75f)
        for ((alignment, top) in expected) {
            val result = read { it.replace("<wp:align>center</wp:align>", "<wp:align>$alignment</wp:align>") }
            val image = result.blocks.filterIsInstance<PdfParagraph>().flatMap { it.anchoredImages }.single()
            assertEquals(PdfBoxAnchor.PAGE, image.anchor)
            assertEquals(top, image.top, .001f)
            assertEquals(outerHeight * 96f / 99f, image.image.height, .001f)
        }
    }

    @Test
    fun `paragraph-relative or wrapped vertical alignment stays outside this profile`() {
        assertThrows(NativeDocxReadException::class.java) {
            read { it.replace("<wp:positionV relativeFrom=\"page\">", "<wp:positionV relativeFrom=\"paragraph\">") }
        }
        assertThrows(NativeDocxReadException::class.java) {
            read { it.replace("<wp:wrapNone/>", "<wp:wrapTopAndBottom/>") }
        }
    }
}

package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.pdf.PdfImageRun
import dev.nuaforma.pdf.PdfParagraph
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordPictureTransformTest {
    @Test
    fun `an asymmetric stored transform preserves the outer inline allocation`() {
        val source = requireNotNull(javaClass.getResourceAsStream("/picture-transform/a4-inline.docx")).use { it.readBytes() }
        val document = NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(source)
        val picture = document.blocks.filterIsInstance<PdfParagraph>().flatMap { it.runs }.filterIsInstance<PdfImageRun>().single()
        assertEquals(96f, picture.image.width, .001f)
        assertEquals(96f * 96f / 99f, picture.image.height, .001f)
        assertEquals(96f, picture.image.height + picture.padding.bottom, .001f)
    }

    @Test
    fun `invalid and excessively different dimensions do not qualify for aspect fitting`() {
        val outer = "1219200"
        assertNull(WordPictureTransform.fit(outer, outer, "0", outer))
        assertNull(WordPictureTransform.fit(outer, outer, "not-a-size", outer))
        assertNull(WordPictureTransform.fit(outer, outer, outer, "1524000"))
    }
}

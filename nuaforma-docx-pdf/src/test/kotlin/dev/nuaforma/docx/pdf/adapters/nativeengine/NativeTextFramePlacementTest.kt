package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationLogging
import dev.nuaforma.contracts.LoggingMode
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeTextFramePlacementTest {
    private val part = "word/document.xml"
    private val source = requireNotNull(javaClass.getResourceAsStream("/floating-drawings/control/a4/source.docx"))
        .use { PdfFixture.unzip(it.readBytes()) }
    private val document = source.getValue(part).decodeToString()
    private val floating = requireNotNull(Regex("<wp:anchor\\b[^>]*>.*?</wp:anchor>", RegexOption.DOT_MATCHES_ALL).find(document)).value
    private val inline = "<wp:inline>" + floating.substring(floating.indexOf("<wp:extent"))
        .replace("<wp:wrapNone/>", "").replace("</wp:anchor>", "</wp:inline>")

    private fun read(xml: String): PdfTextBox = NativeDocxReader(GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))) { _, _, _ -> "fixture" }
        .read(PdfFixture.zip(source + (part to xml.toByteArray()))).blocks.filterIsInstance<PdfTextBox>().single()

    private fun rejected(xml: String, reason: String) {
        assertNotEquals(document, xml)
        val error = assertThrows(NativeDocxReadException::class.java) { read(xml) }
        assertEquals(reason, error.message)
        assertEquals(part, error.part)
    }

    @Test
    fun `a dedicated inline text frame retains its host paragraph and rejects incompatible shape policies`() {
        val hostStart = "<w:body><w:p>"
        val hostGeometry = "<w:pPr><w:jc w:val=\"center\"/><w:spacing w:line=\"360\" w:lineRule=\"auto\"/></w:pPr>"
        val body = document.replace(floating, inline)
            .replaceFirst(Regex("<w:body><w:p><w:pPr>.*?</w:pPr>"), hostStart + hostGeometry)
            .replaceFirst(Regex("</w:drawing></w:r><w:r>.*?F00_BODY</w:t></w:r>"), "</w:drawing></w:r>")
        val frame = read(body)
        assertEquals(PdfBoxAnchor.PARAGRAPH, frame.anchor)
        assertEquals(180f, frame.width)
        assertEquals(40f, frame.height)
        assertEquals(PdfAlignment.CENTER, frame.inlineParagraph?.format?.alignment)
        assertEquals(1.5f, frame.inlineParagraph?.format?.automaticLineSpacing?.multiple)

        val cases = listOf(
            inline.replace("<wp:inline>", "<wp:inline distT=\"1\">") to "Unsupported inline frame distances",
            inline.replace("<wp:inline>", "<wp:inline extra=\"0\">") to "Unsupported inline frame distances",
            inline.replace("<wps:cNvSpPr txBox=\"1\"/>", "<wps:cNvSpPr txBox=\"1\" extra=\"0\"/>") to "Unsupported nonvisual inline frame properties",
            inline.replace("<wps:cNvSpPr txBox=\"1\"/>", "<wps:cNvSpPr txBox=\"1\"><a:spLocks noChangeArrowheads=\"0\"/></wps:cNvSpPr>") to "Unsupported nonvisual inline frame properties",
            inline.replace("<a:xfrm rot=\"0\">", "<a:xfrm rot=\"60000\">") to "Inline text frames require fixed unrotated body geometry",
            inline.replace("<a:noAutofit/>", "<a:spAutoFit/>") to "Inline text frames require fixed unrotated body geometry",
        )
        for ((shape, reason) in cases) rejected(body.replace(inline, shape), reason)
    }

    @Test
    fun `physical frame alignment survives independent horizontal and vertical axes`() {
        fun align(horizontal: String, vertical: String) = document.replace("<wp:posOffset>1270000</wp:posOffset>", "<wp:align>$horizontal</wp:align>")
            .replace("<wp:posOffset>1651000</wp:posOffset>", "<wp:align>$vertical</wp:align>")

        for ((horizontal, vertical) in listOf(
            PdfAlignment.LEFT to PdfVerticalAlignment.TOP,
            PdfAlignment.CENTER to PdfVerticalAlignment.CENTER,
            PdfAlignment.RIGHT to PdfVerticalAlignment.BOTTOM,
        )) {
            val frame = read(align(horizontal.name.lowercase(), vertical.name.lowercase()))
            assertEquals(horizontal, frame.pageAlignment?.horizontal)
            assertEquals(vertical, frame.pageAlignment?.vertical)
            assertEquals(0f, frame.left)
            assertEquals(0f, frame.top)
        }
        rejected(align("inside", "top"), "Unsupported horizontal frame alignment")
        rejected(align("left", "outside"), "Unsupported vertical frame alignment")
        rejected(align("left", "top").replace("<a:xfrm rot=\"0\">", "<a:xfrm rot=\"60000\">"),
            "Physical page alignment requires an unrotated page frame")
    }
}

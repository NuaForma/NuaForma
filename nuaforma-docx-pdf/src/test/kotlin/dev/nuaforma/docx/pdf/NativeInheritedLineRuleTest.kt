package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.pdf.PdfParagraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeInheritedLineRuleTest {

    @Test
    fun `a rule without a line value preserves inherited geometry`() {
        val original = requireNotNull(javaClass.getResourceAsStream("/floating-drawings/control/a4/source.docx")).use { it.readBytes() }
        val parts = PdfFixture.unzip(original)
        val document = parts.getValue("word/document.xml").decodeToString()
        val styles = parts.getValue("word/styles.xml").decodeToString()
        val rules = listOf("auto", "exact", "atLeast")
        for (inherited in rules) {
            val defaults = """<w:pPrDefault><w:pPr><w:spacing w:line="480" w:lineRule="$inherited"/></w:pPr></w:pPrDefault>"""
            val qualifiedStyles = styles.replace("</w:docDefaults>", "$defaults</w:docDefaults>")
            val outcomes = (listOf(null) + rules).map { direct ->
                val rule = direct?.let { """ w:lineRule="$it"""" }.orEmpty()
                val paragraph = """<w:p><w:pPr><w:spacing w:before="120"$rule/></w:pPr><w:r><w:t>LINE_RULE</w:t></w:r></w:p>"""
                val qualifiedDocument = document.substringBefore("<w:body>") + "<w:body>$paragraph<w:sectPr>" + document.substringAfter("<w:sectPr>")
                val input = PdfFixture.zip(parts + mapOf("word/document.xml" to qualifiedDocument.toByteArray(),
                    "word/styles.xml" to qualifiedStyles.toByteArray()))
                val model = NativeDocxReader(GenerationContext()) { _, _, _ -> "control" }.read(input)
                val found = model.blocks.filterIsInstance<PdfParagraph>().single()
                assertEquals(6f, found.spaceBefore)
                Triple(found.format.exactLineHeight, found.format.automaticLineSpacing?.multiple, found.minimumLineHeight)
            }
            val expected = when (inherited) {
                "auto" -> Triple(null, 2f, null)
                "exact" -> Triple(24f, null, null)
                else -> Triple(null, null, 24f)
            }
            assertEquals(List(outcomes.size) { expected }, outcomes, inherited)
        }
    }
}

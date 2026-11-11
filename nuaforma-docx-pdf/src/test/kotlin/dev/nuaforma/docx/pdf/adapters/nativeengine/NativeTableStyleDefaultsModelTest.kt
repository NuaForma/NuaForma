package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeTableStyleDefaultsModelTest {

    private fun source(name: String = "source.docx") = requireNotNull(javaClass.getResourceAsStream("/table-style-defaults/a4/$name")).use { it.readBytes() }

    private fun read(bytes: ByteArray) = NativeDocxReader(GenerationContext(), { _, _, _ -> "fixture" }).read(bytes)

    private fun changed(part: String, transform: (String) -> String): ByteArray {
        val parts = PdfFixture.unzip(source())
        val original = parts.getValue(part).decodeToString()
        val modified = transform(original)
        require(original != modified)
        return PdfFixture.zip(parts.mapValues { (name, bytes) -> if (name == part) modified.toByteArray() else bytes })
    }

    @Test
    fun `default styles based on styles and direct edge overrides resolve to explicit margins`() {
        val input = source()
        val before = input.copyOf()
        val tables = read(input).blocks.filterIsInstance<PdfTable>()
        val controls = read(source("control-source.docx")).blocks.filterIsInstance<PdfTable>()
        assertEquals(4, tables.size)
        assertEquals(listOf(PdfMargins(0f, 5.4f, 0f, 5.4f), PdfMargins(0f, 5.4f, 0f, 5.4f),
            PdfMargins(5.65f, 5.4f, 5.65f, 5.4f), PdfMargins(2f, 2.7f, 4f, 10.8f)), tables.map { it.cellMargins })
        assertEquals(controls.map { it.cellMargins }, tables.map { it.cellMargins })
        assertEquals(controls.map { it.preferredWidth }, tables.map { it.preferredWidth })
        assertNull(tables.first().border)
        assertTrue(tables.drop(1).all { it.border == PdfBorder(.5f, PdfColor(0, 0, 0)) })
        assertEquals(controls.map { it.rows.size }, tables.map { it.rows.size })
        assertArrayEquals(before, input)
    }

    @Test
    fun `decorative conflicting spaced malformed and unknown borders remain explicit failures`() {
        val invalid = listOf<(String) -> String>(
            { it.replaceFirst("w:space=\"0\"", "w:space=\"1\"") },
            { it.replaceFirst("w:space=\"0\"", "extra=\"1\"") },
            { it.replaceFirst("w:val=\"single\"", "w:val=\"double\"") },
            { it.replaceFirst("w:color=\"auto\"", "w:color=\"not-rgb\"") },
            { it.replaceFirst("w:sz=\"0\"", "w:sz=\"-1\"") },
            { it.replaceFirst("w:val=\"none\" w:sz=\"0\"", "w:val=\"single\" w:sz=\"4\"") })
        for (transform in invalid) assertThrows(NativeDocxReadException::class.java) { read(changed("word/document.xml", transform)) }
    }

    @Test
    fun `missing unprovided margin edges do not acquire guessed defaults`() {
        val input = changed("word/document.xml") { it.replaceFirst("<w:tblPr>",
            "<w:tblPr><w:tblStyle w:val=\"NoMargins\"/>") }
        val parts = PdfFixture.unzip(input)
        val modified = parts.mapValues { (name, bytes) -> if (name in setOf("word/styles.xml", "word/stylesWithEffects.xml"))
            bytes.decodeToString().replace("w:default=\"1\" w:styleId=\"NativeDefaultTable\"", "w:default=\"0\" w:styleId=\"NativeDefaultTable\"").replace("</w:styles>", "<w:style w:type=\"table\" w:styleId=\"NoMargins\"><w:name w:val=\"No margins\"/></w:style></w:styles>").toByteArray() else bytes }
        val error = assertThrows(NativeDocxReadException::class.java) { read(PdfFixture.zip(modified)) }
        assertTrue(error.message!!.contains("Explicit cell margins"))
    }

    @Test
    fun `valid unsupported border definitions remain inactive when their table style is unused`() {
        val parts = PdfFixture.unzip(source())
        val definition = """<w:style w:type="table" w:styleId="UnusedDecorative"><w:name w:val="Unused decorative"/><w:tblPr><w:tblBorders><w:top w:val="double" w:sz="8" w:color="112233" w:space="1" w:themeColor="accent1"/></w:tblBorders></w:tblPr></w:style>"""
        val input = PdfFixture.zip(parts.mapValues { (name, bytes) -> if (name in setOf("word/styles.xml", "word/stylesWithEffects.xml"))
            bytes.decodeToString().replace("</w:styles>", "$definition</w:styles>").toByteArray() else bytes })
        val prepared = WordMarkupCompatibility.prepare(input, GenerationContext())
        assertArrayEquals(input, prepared)
        assertEquals(read(source()).blocks.filterIsInstance<PdfTable>().map { it.cellMargins }, read(prepared).blocks.filterIsInstance<PdfTable>().map { it.cellMargins })
    }

}

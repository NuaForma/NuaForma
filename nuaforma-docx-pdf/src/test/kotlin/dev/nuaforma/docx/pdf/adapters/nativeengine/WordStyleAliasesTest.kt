package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordStyleAliasesTest {

    private fun read(alias: String, reference: String? = null, misplaced: Boolean = false) {
        val parts = PdfFixture.parts()
        val name = if (misplaced) "word/document.xml" else "word/styles.xml"
        val marker = if (misplaced) "<w:p>" else "</w:style>"
        parts[name] = parts.getValue(name).decodeToString().replace(marker, if (misplaced) "<w:p>$alias" else "$alias</w:style>").toByteArray()
        if (reference != null) parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
            .replace("<w:p>", "<w:p><w:pPr><w:pStyle w:val=\"$reference\"/></w:pPr>").toByteArray()
        NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(PdfFixture.zip(parts))
    }

    @Test
    fun `alternate display names accept empty comma-separated Unicode and schema-boundary values`() {
        for (value in listOf("", "Business body,Document body", "Ελληνικά,Кириллица", "a".repeat(253), "\uD83D\uDE00".repeat(253)))
            assertDoesNotThrow { read("<w:aliases w:val=\"$value\"/>") }
    }

    @Test
    fun `malformed duplicate foreign nested oversized and misplaced aliases remain errors`() {
        for (bad in listOf("<w:aliases/>", "<w:aliases val=\"Body\"/>", "<w:aliases w:val=\"Body\" extra=\"1\"/>",
            "<w:aliases w:val=\"Body\"/><w:aliases w:val=\"Other\"/>", "<w:aliases w:val=\"Body\">text</w:aliases>",
            "<w:aliases w:val=\"Body\"><w:r/></w:aliases>", "<x:aliases xmlns:x=\"urn:other\" w:val=\"Body\"/>",
            "<w:aliases w:val=\"${"a".repeat(254)}\"/>"))
            assertThrows(NativeDocxReadException::class.java, { read(bad) }, bad)
        assertThrows(NativeDocxReadException::class.java) { read("<w:aliases w:val=\"Body\"/>", misplaced = true) }
    }

    @Test
    fun `style references continue to use IDs rather than alternate display names`() {
        assertDoesNotThrow { read("<w:aliases w:val=\"Business body\"/>", "Normal") }
        assertThrows(NativeDocxReadException::class.java) { read("<w:aliases w:val=\"Business body\"/>", "Business body") }
    }

}

package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordAuthoringMetadataTest {

    @Test
    fun `next paragraph style is passive even for missing self and cyclic editor targets`() {
        val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

        fun styles(next: String) = ConversionXml(GenerationContext()).read(
            """<w:styles xmlns:w="$word"><w:style w:type="paragraph" w:styleId="Body">$next<w:basedOn w:val="Normal"/><w:rPr><w:b/></w:rPr></w:style></w:styles>""".toByteArray())

        for (target in listOf("Body", "Missing", "Other Style", "x".repeat(253))) {
            val root = styles("<w:next w:val=\"$target\"/>")
            WordAuthoringMetadata.normalize(root, "word/styles.xml", false)
            assertEquals(listOf("basedOn", "rPr"), root.elements().single().elements().map { it.name.localPart })
        }
        for (bad in listOf("<w:next/>", "<w:next w:val=\"\"/>", "<w:next val=\"Normal\"/>",
            "<w:next w:val=\"${"x".repeat(254)}\"/>", "<w:next w:val=\"Normal\" extra=\"1\"/>",
            "<w:next w:val=\"Normal\">text</w:next>", "<w:next w:val=\"Normal\"><w:p/></w:next>",
            "<w:next w:val=\"Normal\"/><w:next w:val=\"Body\"/>")) {
            assertThrows(NativeDocxReadException::class.java, { WordAuthoringMetadata.normalize(styles(bad), "word/styles.xml", false) }, bad)
        }
    }

    @Test
    fun `style revision metadata has no print behavior and only accepts a four byte hexadecimal identifier`() {
        val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

        fun styles(revision: String) = ConversionXml(GenerationContext()).read(
            """<w:styles xmlns:w="$word"><w:style w:type="paragraph" w:styleId="Body">$revision<w:rPr><w:b/></w:rPr></w:style></w:styles>""".toByteArray())

        for (value in listOf("00000000", "FFFFFFFF", "3E412d01")) {
            val root = styles("""<w:rsid w:val="$value"> </w:rsid>""")
            WordAuthoringMetadata.normalize(root, "word/styles.xml", false)
            assertEquals("rPr", root.elements().single().elements().single().name.localPart)
        }
        for (revision in listOf("""<w:rsid/>""", """<w:rsid w:val="FFFF"/>""", """<w:rsid w:val="000000000"/>""",
            """<w:rsid w:val="0000000G"/>""", """<w:rsid val="00000001"/>""", """<w:rsid w:val=" 00000001"/>""",
            """<w:rsid w:val="00000001" extra="1"/>""", """<w:rsid w:val="00000001"><w:r/></w:rsid>""",
            """<w:rsid w:val="00000001">content</w:rsid>""", """<w:rsid w:val="00000001"/><w:rsid w:val="00000002"/>""")) {
            assertThrows(NativeDocxReadException::class.java) { WordAuthoringMetadata.normalize(styles(revision), "word/styles.xml", false) }
        }
    }

    @Test
    fun `linked styles keep independent formatting definitions and validate pairing metadata`() {
        val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

        fun styles(link: String) = ConversionXml(GenerationContext()).read(
            """<w:styles xmlns:w="$word"><w:style w:type="paragraph" w:styleId="Heading">$link<w:rPr><w:b/></w:rPr></w:style></w:styles>""".toByteArray())

        val valid = styles("""<w:link w:val="HeadingChar"/>""")
        WordAuthoringMetadata.normalize(valid, "word/styles.xml", false)
        assertEquals("rPr", valid.elements().single().elements().single().name.localPart)
        for (link in listOf("<w:link/>", "<w:link w:val=\"\"/>",
            "<w:link w:val=\"x\" w:extra=\"1\"/>", "<w:link w:val=\"x\"><w:r/></w:link>",
            "<w:link w:val=\"x\"/><w:link w:val=\"y\"/>")) {
            assertThrows(NativeDocxReadException::class.java) { WordAuthoringMetadata.normalize(styles(link), "word/styles.xml", false) }
        }
    }

    private fun paragraph(content: String) = ConversionXml(GenerationContext()).read(
        ("<w:p xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" + content + "</w:p>").toByteArray())

    @Test fun `qualified proofing markers are passive while visible text remains unchanged`() {
        val root = paragraph("<w:r><w:t>Before</w:t></w:r>" +
            listOf("spellStart", "spellEnd", "gramStart", "gramEnd").joinToString("") { "<w:proofErr w:type=\"$it\"/>" } +
            "<w:r><w:t>After</w:t></w:r>")
        WordAuthoringMetadata.normalize(root, "word/document.xml", false)
        assertEquals(listOf("r", "r"), root.elements().map { it.name.localPart })
    }

    @Test fun `proofing markers cannot disguise unsupported attributes or child content`() {
        for (xml in listOf("<w:proofErr/>", "<w:proofErr w:type=\"other\"/>",
            "<w:proofErr w:type=\"spellStart\" w:other=\"1\"/>",
            "<w:proofErr w:type=\"spellStart\"><w:r><w:t>Hidden content</w:t></w:r></w:proofErr>")) {
            assertThrows(NativeDocxReadException::class.java) {
                WordAuthoringMetadata.normalize(paragraph(xml), "word/document.xml", false)
            }
        }
    }

    @Test fun `paragraph revision and collaboration identifiers do not change note separator structure`() {
        val root = paragraph("<w:r><w:separator/></w:r>")
        val word = root.name.namespaceURI
        val collaboration = "http://schemas.microsoft.com/office/word/2010/wordml"
        for ((namespace, name) in listOf(word to "rsidRDefault", collaboration to "paraId", collaboration to "textId"))
            root.attributes[javax.xml.namespace.QName(namespace, name)] = "00000001"
        WordAuthoringMetadata.normalize(root, "word/footnotes.xml", false)
        assertTrue(root.attributes.isEmpty())
        assertEquals("separator", root.elements().single().elements().single().name.localPart)
        root.attributes[javax.xml.namespace.QName(collaboration, "unknown")] = "00000001"
        WordAuthoringMetadata.normalize(root, "word/footnotes.xml", false)
        assertEquals(1, root.attributes.size)
    }

    @Test fun `inactive complex script face metadata cannot change Latin size bold or italic`() {
        val content = "<w:r><w:rPr><w:sz w:val=\"20\"/><w:szCs w:val=\"28\"/><w:bCs/><w:iCs w:val=\"1\"/></w:rPr><w:t>Latin</w:t></w:r>"
        val root = paragraph(content)
        WordAuthoringMetadata.normalize(root, "word/document.xml", false)
        assertEquals(listOf("sz"), root.elements().single().elements().first().elements().map { it.name.localPart })
        val complex = paragraph(content)
        WordAuthoringMetadata.normalize(complex, "word/document.xml", true)
        assertEquals(4, complex.elements().single().elements().first().elements().size)
        for (property in listOf("<w:szCs w:val=\"bad\"/>", "<w:szCs w:val=\"0\"/>", "<w:bCs w:val=\"bad\"/>",
            "<w:iCs unexpected=\"1\"/>", "<w:szCs w:val=\"20\"><w:t>hidden</w:t></w:szCs>"))
            assertThrows(NativeDocxReadException::class.java) { WordAuthoringMetadata.normalize(paragraph("<w:r><w:rPr>$property</w:rPr></w:r>"), "word/document.xml", false) }
    }
}

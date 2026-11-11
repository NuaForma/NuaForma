package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordAuxiliaryPartsTest {

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun parts() = PdfFixture.unzip(resource("/passive-office-package/a4/source.docx")).toMutableMap()

    private fun normalize(bytes: Map<String, ByteArray>, context: GenerationContext = GenerationContext()): MutableMap<String, XmlElement> {
        val xml = ConversionXml(GenerationContext())
        val parsed = bytes.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.mapValues { (name, value) -> xml.readPart(value, name) }.toMutableMap()
        WordAuxiliaryParts(context) { part, _, reason -> NativeDocxReadException(reason, part) }.normalize(parsed, bytes.keys)
        return parsed
    }

    @Test
    fun `equivalent style font and theme graphs are excluded only from the private print model`() {
        val input = parts()
        val snapshot = input.mapValues { it.value.copyOf() }
        val result = normalize(input)
        assertFalse(result.containsKey("word/stylesWithEffects.xml"))
        assertFalse(result.containsKey("word/_rels/fontTable.xml.rels"))
        assertFalse(result.containsKey("word/theme/_rels/theme1.xml.rels"))
        assertTrue(result.getValue("word/fontTable.xml").descendants().none { it.name.localPart.startsWith("embed") })
        for ((name, bytes) in snapshot) assertArrayEquals(bytes, input.getValue(name), name)
    }

    @Test
    fun `style divergence references and malformed graphs fail at the offending part`() {
        val original = parts()
        val name = "word/stylesWithEffects.xml"
        for (edit in listOf<(String) -> String>(
            { it.replace("w:val=\"22\"", "w:val=\"26\"") },
            { it.replace("</w:style>", "<w:rPr><w:color w:val=\"FF0000\"/></w:rPr></w:style>") },
            { it.replace("w:styleId=\"Normal\"", "w:styleId=\"Missing\"") })) {
            val modified = original.toMutableMap()
            val before = modified.getValue(name).decodeToString()
            val after = edit(before)
            assertNotEquals(before, after)
            modified[name] = after.toByteArray()
            assertThrows(NativeDocxReadException::class.java) { normalize(modified) }
        }
        for (target in listOf("../stylesWithEffects.xml", "https://never.invalid/styles.xml")) {
            val modified = original.toMutableMap()
            val rel = "word/_rels/document.xml.rels"
            modified[rel] = modified.getValue(rel).decodeToString().replace("Target=\"stylesWithEffects.xml\"", "Target=\"$target\"").toByteArray()
            assertThrows(NativeDocxReadException::class.java) { normalize(modified) }
        }
    }

    @Test
    fun `font ownership obfuscation identity and theme targets stay closed`() {
        for ((name, transform) in listOf<Pair<String, (String) -> String>>(
            "word/_rels/fontTable.xml.rels" to { it.replace("Target=\"fonts/fixture.odttf\"", "Target=\"../fixture.odttf\"") },
            "word/_rels/fontTable.xml.rels" to { it.replace("Id=\"font233\"", "Id=\"missing\"") },
            "word/fontTable.xml" to { it.replace("{11111111-2222-3333-4444-555555555555}", "invalid-key") },
            "word/theme/_rels/theme1.xml.rels" to { it.replace("Target=", "TargetMode=\"External\" Target=") },
            "word/theme/_rels/theme1.xml.rels" to { it.replace("../media/theme233.png", "../../theme233.png") })) {
            val modified = parts()
            val before = modified.getValue(name).decodeToString()
            val after = transform(before)
            assertNotEquals(before, after)
            modified[name] = after.toByteArray()
            assertThrows(NativeDocxReadException::class.java) { normalize(modified) }
        }
        val orphan = parts().apply { this["word/fonts/orphan.ttf"] = byteArrayOf(1, 2, 3) }
        assertThrows(NativeDocxReadException::class.java) { normalize(orphan) }
    }

    @Test
    fun `auxiliary admission observes cancellation`() {
        assertThrows(GenerationException::class.java) { normalize(parts(), GenerationContext(cancellation = CancellationToken { true })) }
    }

    @Test
    fun `serialized style booleans compare semantically without hiding changed values`() {
        val input = parts()
        val metadata = """<w:latentStyles w:defLockedState="0" w:defSemiHidden="1" w:defUnhideWhenUsed="1" w:defQFormat="0"><w:lsdException w:name="Example" w:locked="0" w:semiHidden="0" w:unhideWhenUsed="1" w:qFormat="1"/></w:latentStyles>"""
        for (name in listOf("word/styles.xml", "word/stylesWithEffects.xml")) {
            val value = if (name == "word/styles.xml") metadata.replace("=\"0\"", "=\"false\"").replace("=\"1\"", "=\"true\"") else metadata
            val paragraph = """<w:adjustRightInd w:val="0"/><w:spacing w:beforeAutospacing="1" w:afterAutospacing="1"/>"""
            val properties = if (name == "word/styles.xml") paragraph.replace("=\"0\"", "=\"false\"").replace("=\"1\"", "=\"true\"") else paragraph
            input[name] = input.getValue(name).decodeToString().replace("</w:styles>", value + "</w:styles>")
                .replace("<w:pPr>", "<w:pPr>$properties").toByteArray()
        }
        assertDoesNotThrow { normalize(input) }
        val name = "word/stylesWithEffects.xml"
        input[name] = input.getValue(name).decodeToString().replace("w:defLockedState=\"0\"", "w:defLockedState=\"1\"").toByteArray()
        assertThrows(NativeDocxReadException::class.java) { normalize(input) }
    }

    @Test
    fun `unused supplemental ordinary styles are allowed but used or default styles cannot disappear`() {
        val input = parts()
        val styles = "word/styles.xml"
        val extra = """<w:style w:type="paragraph" w:styleId="Unused233"><w:name w:val="Unused233"/><w:basedOn w:val="Normal"/></w:style>"""
        input[styles] = input.getValue(styles).decodeToString().replace("</w:styles>", extra + "</w:styles>").toByteArray()
        assertDoesNotThrow { normalize(input) }
        val used = input.toMutableMap()
        used["word/document.xml"] = used.getValue("word/document.xml").decodeToString().replace("<w:pPr>", "<w:pPr><w:pStyle w:val=\"Unused233\"/>").toByteArray()
        val failure = assertThrows(NativeDocxReadException::class.java) { normalize(used) }
        assertEquals("word/stylesWithEffects.xml", failure.part)
        input[styles] = input.getValue(styles).decodeToString().replace("w:styleId=\"Unused233\"", "w:styleId=\"Unused233\" w:default=\"1\"").toByteArray()
        assertThrows(NativeDocxReadException::class.java) { normalize(input) }
    }

}

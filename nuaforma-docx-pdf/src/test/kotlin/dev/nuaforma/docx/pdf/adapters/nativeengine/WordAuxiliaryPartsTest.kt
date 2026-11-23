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
    fun `ordinary typography remains available when the effects copy omits it`() {
        val modified = parts()
        val styles = "word/styles.xml"
        val main = modified.getValue(styles).decodeToString()
        val typography = """<w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="24"/><w:color w:val="112233"/></w:rPr>"""
        assertTrue("</w:style>" in main)
        modified[styles] = main.replaceFirst("</w:style>", typography + "</w:style>").toByteArray()
        val qualified = normalize(modified)
        assertTrue(qualified.getValue(styles).descendants().any { it.name.localPart == "color" })
        modified[styles] = modified.getValue(styles).decodeToString().replace("""<w:color w:val="112233"/>""", "<w:shadow/>").toByteArray()
        assertThrows(NativeDocxReadException::class.java) { normalize(modified) }
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
    fun `supplemental ordinary paragraph styles resolve applied uses while default and wrong-kind styles reject`() {
        val input = parts()
        val styles = "word/styles.xml"
        val extra = """<w:style w:type="paragraph" w:styleId="Unused233"><w:name w:val="Unused233"/><w:basedOn w:val="Normal"/></w:style>"""
        input[styles] = input.getValue(styles).decodeToString().replace("</w:styles>", extra + "</w:styles>").toByteArray()
        assertDoesNotThrow { normalize(input) }
        val used = input.toMutableMap()
        used["word/document.xml"] = used.getValue("word/document.xml").decodeToString().replace("<w:pPr>", "<w:pPr><w:pStyle w:val=\"Unused233\"/>").toByteArray()
        assertDoesNotThrow { normalize(used) }
        used[styles] = used.getValue(styles).decodeToString().replace(
            """w:type="paragraph" w:styleId="Unused233"""", """w:type="character" w:styleId="Unused233"""").toByteArray()
        val failure = assertThrows(NativeDocxReadException::class.java) { normalize(used) }
        assertEquals("word/stylesWithEffects.xml", failure.part)
        input[styles] = input.getValue(styles).decodeToString().replace("w:styleId=\"Unused233\"", "w:styleId=\"Unused233\" w:default=\"1\"").toByteArray()
        assertThrows(NativeDocxReadException::class.java) { normalize(input) }
    }


    @Test
    fun `ordinary-only links must be reciprocal paragraph and character pairs`() {
        val input = parts()
        val styles = "word/styles.xml"
        val before = input.getValue(styles).decodeToString()
        val first = """<w:style w:type="paragraph" w:styleId="LinkedParagraph"><w:name w:val="Linked paragraph"/><w:link w:val="LinkedCharacter"/></w:style>"""
        val second = """<w:style w:type="character" w:styleId="LinkedCharacter"><w:name w:val="Linked character"/><w:link w:val="LinkedParagraph"/></w:style>"""
        input[styles] = before.replace("</w:styles>", first + second + "</w:styles>").toByteArray()
        assertDoesNotThrow { normalize(input) }
        input[styles] = before.replace("</w:styles>", first + second.replace("""<w:link w:val="LinkedParagraph"/>""", "") + "</w:styles>").toByteArray()
        assertThrows(NativeDocxReadException::class.java) { normalize(input) }
    }

    @Test
    fun `an ordinary-only typography style must keep its edit metadata and explicit font bounds`() {
        val name = "word/styles.xml"
        val original = parts()
        val styles = original.getValue(name).decodeToString()
        val typography = """<w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/>
            <w:sz w:val="400"/><w:color w:val="aAbBcC"/></w:rPr>"""
        fun qualified(ordinary: String) = normalize(original + (name to styles.replaceFirst("</w:style>",
            ordinary + "</w:style>").toByteArray()))
        val accepted = qualified(typography).getValue(name)
        assertTrue(accepted.descendants().any { it.name.localPart == "sz" && it.attribute(PdfFixture.W, "val") == "400" })
        for (bad in listOf(
            "<w:rPr/>", typography.replace("<w:rPr>", "<w:rPr w:val=\"1\">"), typography.replace("<w:rPr>", "<w:rPr>text"),
            typography.replace("</w:rPr>", "<w:sz w:val=\"22\"/></w:rPr>"), typography.replace("w:sz", "w:szCs"),
            typography.replace("w:val=\"400\"", "w:val=\"401\""), typography.replace("w:val=\"400\"", "w:val=\"1\""),
            typography.replace("w:val=\"400\"", "w:val=\"bad\""), typography.replace("w:val=\"400\"", "w:other=\"400\""),
            typography.replace("w:hAnsi=\"Liberation Sans\"", "w:hAnsi=\"Another family\""),
            typography.replace("Liberation Sans", "x".repeat(129)), typography.replace("Liberation Sans", " &#x9; "),
            typography.replace("Liberation Sans", "Liberation&#x9;Sans"), typography.replace("w:hAnsi=", "w:other="),
            typography.replace("w:val=\"aAbBcC\"", "w:val=\"auto\""), typography.replace("w:val=\"aAbBcC\"", "w:themeColor=\"accent1\""),
            typography.replace("w:val=\"aAbBcC\"/>", "w:val=\"aAbBcC\">text</w:color>"),
        )) {
            val error = assertThrows(NativeDocxReadException::class.java) { qualified(bad) }
            assertEquals("word/stylesWithEffects.xml", error.part)
            assertEquals("Active or divergent styles-with-effects formatting is not qualified", error.message)
        }
    }

}

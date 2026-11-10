package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeSequenceCodeFormattingTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val xml = ConversionXml(GenerationContext())

    private fun parse(code: String) = NativeDocxFields.instruction(code) { error(it) }

    private fun root(content: String) = xml.read("""<w:document xmlns:w="$word"><w:body><w:p>$content</w:p></w:body></w:document>""".toByteArray())

    private fun run(code: String, properties: String = "") = "<w:r>$properties<w:instrText xml:space=\"preserve\">$code</w:instrText></w:r>"

    private fun field(instruction: String) = "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>$instruction<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>999</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"

    @Test
    fun `sequence formatting switches retain their distinct meaning and reject conflicts`() {
        assertFalse(parse("SEQ Probe").mergeFormat)
        assertFalse(parse("SEQ Probe").charFormat)
        assertTrue(parse("SEQ Probe \\* mergeformat").mergeFormat)
        assertTrue(parse("SEQ Probe \\* charformat").charFormat)
        for (code in listOf("SEQ Probe \\* CHARFORMAT \\* MERGEFORMAT", "SEQ Probe \\* CHARFORMAT \\* CHARFORMAT",
            "PAGE \\* CHARFORMAT", "REF Target \\* CHARFORMAT", "FORMTEXT \\* CHARFORMAT")) {
            assertThrows(IllegalStateException::class.java) { parse(code) }
        }
    }

    @Test
    fun `the first actual code character owns formatting and later fields inherit independently`() {
        val properties = "<w:rPr><w:color w:val=\"AA0000\"/><w:sz w:val=\"24\"/></w:rPr>"
        val first = field(run(" ", "<w:rPr><w:sz w:val=\"36\"/></w:rPr>") + run(" S", properties) +
            run("EQ Probe ", "<w:rPr><w:sz w:val=\"28\"/></w:rPr>"))
        val root = root(first + field(run(" SEQ Probe ")))
        val sourceProperty = root.descendants().first { it.name.localPart == "rPr" && it.elements().any { child -> child.name.localPart == "color" } }
        val recorded = java.util.IdentityHashMap<XmlElement, XmlElement?>()
        NativeDocxFields.normalize(root, { error(it) }, formatting = { field, style -> recorded[field] = style })
        val fields = root.descendants().filter { it.name.localPart == "fldSimple" }.toList()
        assertEquals(2, fields.size)
        assertSame(sourceProperty, recorded[fields[0]])
        assertTrue(recorded.containsKey(fields[1]))
        assertNull(recorded[fields[1]])
        assertEquals(2, Regex("999").findAll(xml.write(root).decodeToString()).count())
    }

    @Test
    fun `original malformed code flags namespaces and duplicates remain source failures`() {
        for (properties in listOf("<w:rPr/><w:rPr/>", "<w:rPr><w:b w:val=\"invalid\"/></w:rPr>",
            "<w:rPr><w:i val=\"true\"/></w:rPr>", "<w:rPr><x:sz xmlns:x=\"urn:future\" w:val=\"28\"/></w:rPr>",
            "<w:rPr><w:sz w:val=\"28\"/><w:sz w:val=\"28\"/></w:rPr>")) {
            var focus: XmlElement? = null
            assertThrows(IllegalStateException::class.java) {
                NativeDocxFields.validateCodeSource(root(field(run(" SEQ Probe ", properties)))) { node, message -> focus = node; error(message) }
            }
            assertNotNull(focus)
        }
    }

    @Test
    fun `duplicate code run properties are rejected while a simple field has no invented physical code`() {
        val invalid = root(field(run(" SEQ Probe ", "<w:rPr/><w:rPr/>")))
        assertThrows(IllegalStateException::class.java) { NativeDocxFields.normalize(invalid, { error(it) }) }
        val simple = root("<w:fldSimple w:instr=\"SEQ Probe\"><w:r><w:t>999</w:t></w:r></w:fldSimple>")
        var calls = 0
        NativeDocxFields.normalize(simple, { error(it) }, formatting = { _, _ -> calls++ })
        assertEquals(0, calls)
        assertTrue(xml.write(simple).decodeToString().contains("999"))
    }
}

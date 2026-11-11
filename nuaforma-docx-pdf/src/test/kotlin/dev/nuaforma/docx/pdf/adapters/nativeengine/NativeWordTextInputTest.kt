package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeWordTextInputTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val xml = ConversionXml(GenerationContext())

    private fun root(input: String) = xml.read("""<w:ffData xmlns:w="$word"><w:name w:val="Text1"/><w:enabled/><w:calcOnExit w:val="1"/><w:textInput>$input</w:textInput></w:ffData>""".toByteArray())

    private fun validate(root: XmlElement) = NativeWordTextInput.validate(root, {}) { throw IllegalArgumentException(it) }

    @Test
    fun `regular editing defaults and length constraints never mutate stored XML`() {
        for (properties in listOf("", """<w:type w:val="regular"/><w:default w:val="Не выводить {missing"/><w:maxLength w:val="2"/><w:format w:val="Uppercase"/>""",
            """<w:maxLength w:val="0"/><w:format w:val=""/>""")) {
            val root = root(properties)
            val before = xml.write(root)
            validate(root)
            assertArrayEquals(before, xml.write(root))
        }
    }

    @Test
    fun `empty editing names and uppercase spelling retain current stored state`() {
        for (format in listOf("Uppercase", "UPPERCASE", "uppercase", "uPpErCaSe")) {
            val input = root("""<w:format w:val="$format"/>""")
            input.elements().single { it.name.localPart == "name" }.attributes[
                javax.xml.namespace.QName(word, "val")] = ""
            val before = xml.write(input)
            validate(input)
            assertArrayEquals(before, xml.write(input))
        }
        for (name in listOf("<w:name/>", "<w:name w:val=\"Text field\"/>", "<w:name w:val=\"" + "A".repeat(129) + "\"/>")) {
            val invalid = xml.read("""<w:ffData xmlns:w="$word">$name<w:textInput/></w:ffData>""".toByteArray())
            assertThrows(IllegalArgumentException::class.java) { validate(invalid) }
        }
        for (format in listOf(" UPPERCASE", "UPPERCASE ", "Uppercaſe", "Lowercase", "yyyy-MM-dd")) {
            assertThrows(IllegalArgumentException::class.java) { validate(root("""<w:format w:val="$format"/>""")) }
        }
    }

    @Test
    fun `active unknown malformed and duplicate metadata remain rejected`() {
        for (bad in listOf("""<w:type w:val="date"/>""", """<w:type w:val="calculated"/>""",
            """<w:format w:val="yyyy-MM-dd"/>""", """<w:maxLength w:val="-1"/>""",
            """<w:maxLength w:val="32768"/>""", """<w:default/>""",
            """<w:type w:val="regular"/><w:type w:val="regular"/>""",
            """<w:default w:val="X" unexpected="1"/>""", """<f:type xmlns:f="urn:foreign" w:val="regular"/>""",
            """<w:default w:val="X"><w:t>payload</w:t></w:default>""")) {
            assertThrows(IllegalArgumentException::class.java, { validate(root(bad)) }, bad)
        }
        val macro = root("")
        macro.children += xml.read("""<w:entryMacro xmlns:w="$word" w:val="NeverRun"/>""".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { validate(macro) }
    }

    @Test
    fun `raw original form metadata is checked before a binder can drop foreign properties`() {
        val root = root("""<w:format val="Uppercase"/>""")
        var focus: XmlElement? = null
        assertThrows(IllegalArgumentException::class.java) {
            NativeWordTextInput.validateSource(root, { focus = it }) { throw IllegalArgumentException(it) }
        }
        assertEquals("format", focus?.name?.localPart)
    }

    @Test
    fun `text input opcodes keep the closed field grammar`() {
        assertEquals("FORMTEXT", NativeDocxFields.instruction(" formtext \\* MERGEFORMAT ") { error(it) }.kind)
        for (code in listOf("FORMTEXT \\* ARABIC", "FORMTEXT \\@ yyyy", "FORMTEXT extra", "FORMTEXT \\* MERGEFORMAT \\* MERGEFORMAT")) {
            assertThrows(IllegalArgumentException::class.java) { NativeDocxFields.instruction(code) { throw IllegalArgumentException(it) } }
        }
    }
}

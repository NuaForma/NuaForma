package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeDocxFieldsTest {
    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    private fun parse(code: String) = NativeDocxFields.instruction(code, ::fail)
    private fun normalize(content: String): String {
        val xml = ConversionXml(GenerationContext())
        val root = xml.read(("<w:body xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">$content</w:body>").toByteArray())
        NativeDocxFields.normalize(root, ::fail)
        return xml.write(root).decodeToString()
    }
    private fun control(value: String) = "<w:r><w:fldChar w:fldCharType=\"$value\"/></w:r>"
    private fun instruction(code: String) = "<w:r><w:instrText>$code</w:instrText></w:r>"

    @Test fun `field grammar keeps only explicit decimal and linked reference policies`() {
        val sequence = parse("seq Figures \\r 4 \\* ARABIC \\* MERGEFORMAT")
        assertEquals(4, sequence.restart)
        assertEquals("Figures", sequence.argument)
        assertTrue(parse("SEQ Figures \\c").repeat)
        assertEquals(1, parse("SEQ Figures \\s 2").headingReset)
        assertEquals("REF", parse("ref \"_Ref123\" \\h").kind)
        assertTrue(parse("PAGEREF _Ref123 \\h").linked)
        val contents = parse("TOC \\o \"2-4\" \\h \\u \\z")
        assertEquals(1, contents.firstLevel); assertEquals(3, contents.lastLevel)
        assertFalse(parse("PAGE \\* ARABIC").linked)
    }

    @Test fun `malformed unknown excessive and contradictory field instructions reject`() {
        for (code in listOf("", "a".repeat(2_049), "REF \"unclosed", "REF \"_Ref\"tail", "REF bad/name", "REF", "DDEAUTO server", "SEQ X \\s 10",
            "SEQ X \\r -1", "SEQ X \\r 2 \\r 3", "SEQ X \\c \\n", "SEQ X \\r 2 \\s 1", "SEQ X \\* ROMAN", "SEQ X \\* ARABIC \\* ARABIC",
            "TOC", "TOC \\o \"4-2\"", "TOC \\o \"1-10\"", "TOC \\o \"1-x\"", "PAGE \\h", "REF _Ref \\p", "PAGE\u0000"))
            assertThrows(IllegalArgumentException::class.java, { parse(code) }, code)
    }

    @Test fun `complex fields preserve complete cached result bookmarks and ignore the cached value`() {
        val start = "<w:bookmarkStart w:id=\"1\" w:name=\"_Ref\"/>"
        val end = "<w:bookmarkEnd w:id=\"1\"/>"
        val field = control("begin") + instruction("SEQ Figures") + control("separate") + start + "<w:r><w:t>999</w:t></w:r>" + end + control("end")
        val result = normalize("<w:p>$field</w:p>")
        assertTrue(result.indexOf("bookmarkStart") < result.indexOf("fldSimple"))
        assertTrue(result.lastIndexOf("bookmarkEnd") > result.lastIndexOf("fldSimple"))
        assertFalse(result.contains("fldChar"))
        for (invalid in listOf(control("end"), control("separate"), control("begin"), control("begin") + instruction("PAGE") + control("end"),
            control("begin") + instruction("PAGE") + control("separate") + control("separate"), control("unknown"),
            control("begin") + instruction("PAGE") + control("begin"),
            field.replace(control("begin"), "<w:r><w:fldChar w:fldCharType=\"begin\" w:fldLock=\"1\"/></w:r>"),
            field.replace(start, "<w:r><w:t>partial</w:t></w:r>$start"), field.replace(start, "<w:sdt/>$start")))
            assertThrows(IllegalArgumentException::class.java) { normalize("<w:p>$invalid</w:p>") }
    }

    @Test fun `only identified standalone contents may replace a multi paragraph field cache`() {
        val begin = control("begin") + instruction("TOC \\o &quot;1-2&quot; \\h") + control("separate")
        val cache = "<w:r><w:t>outdated</w:t></w:r>"
        val content = "<w:p>$begin$cache</w:p><w:p>$cache${control("end")}</w:p>"
        val result = normalize(content)
        assertEquals(1, Regex("<w:p>").findAll(result).count())
        assertTrue(result.contains("fldSimple"))
        val followed = normalize(content + "<w:p><w:r><w:t>Following body content</w:t></w:r></w:p>")
        assertEquals(2, Regex("<w:p>").findAll(followed).count())
        assertTrue(followed.contains("Following body content"))
        assertThrows(IllegalArgumentException::class.java) { normalize(content.replace("TOC \\o &quot;1-2&quot; \\h", "REF _Ref")) }
        assertThrows(IllegalArgumentException::class.java) { normalize(content.replace("<w:p>$begin", "<w:p>$cache$begin")) }
        val control = "<w:sdt><w:sdtPr><w:id w:val=\"1\"/><w:docPartObj><w:docPartGallery w:val=\"Table of Contents\"/><w:docPartUnique/></w:docPartObj></w:sdtPr><w:sdtContent>$content</w:sdtContent></w:sdt>"
        assertFalse(normalize(control).contains("sdt"))
        assertTrue(normalize(control.replace("Table of Contents", "Other gallery")).contains("sdt"))
    }
}

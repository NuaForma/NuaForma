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

    @Test
    fun `ordinary decimal picture leaves the qualified live page fields unchanged`() {
        for (kind in listOf("PAGE", "NUMPAGES", "SECTIONPAGES")) {
            assertEquals(parse("$kind \\* MERGEFORMAT"), parse("""$kind \# "0" \* MERGEFORMAT"""))
            assertEquals(parse(kind), parse("$kind \\# 0"))
        }
        for (invalid in listOf("""PAGE \# "00"""", """PAGE \# "#,##0"""", "PAGE \\#",
            "PAGE \\# 0 \\# 0", "PAGEREF _Here \\# 0", "SEQ Example \\# 0")) {
            assertThrows(IllegalArgumentException::class.java) { parse(invalid) }
        }
    }

    @Test
    fun `nonprinting literal SET fields do not require a result separator or cached sample`() {
        val content = "<w:p>${control("begin")}${instruction("SET Choice &quot;M&quot;")}${control("end")}</w:p>"
        val result = normalize(content)
        assertTrue(result.contains("fldSimple") && result.contains("SET Choice"))
        assertFalse(result.contains("fldChar"))
        assertThrows(IllegalArgumentException::class.java) { normalize(content.replace("SET Choice &quot;M&quot;", "DOCPROPERTY Title")) }
    }

    @Test
    fun `complex checkbox metadata remains associated with its field and cannot hide in other controls`() {
        val xml = ConversionXml(GenerationContext())
        val form = "<w:ffData><w:checkBox><w:sizeAuto/><w:default w:val=\"0\"/></w:checkBox></w:ffData>"
        val begin = "<w:r><w:rPr><w:b/></w:rPr><w:fldChar w:fldCharType=\"begin\">$form</w:fldChar></w:r>"
        val content = "<w:p>$begin${instruction("FORMCHECKBOX")}${control("separate")}<w:r><w:t>stale</w:t></w:r>${control("end")}</w:p>"
        val root = xml.read("""<w:body xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">$content</w:body>""".toByteArray())
        val forms = java.util.IdentityHashMap<dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement, NativeDocxFields.Form>()
        NativeDocxFields.normalize(root, ::fail, forms)
        val field = root.elements().single().elements().single()
        assertSame(forms.keys.single(), field)
        assertEquals("ffData", forms.getValue(field).data.name.localPart)
        assertEquals("b", forms.getValue(field).properties!!.elements().single().name.localPart)
        assertThrows(IllegalArgumentException::class.java) { normalize(content.replace("FORMCHECKBOX", "PAGE")) }
        assertThrows(IllegalArgumentException::class.java) { normalize(content.replace(form, "")) }
        assertThrows(IllegalArgumentException::class.java) { normalize(content.replace("w:fldCharType=\"begin\"", "w:fldCharType=\"separate\"")) }
        assertThrows(IllegalArgumentException::class.java) { normalize(content.replace("<w:rPr><w:b/></w:rPr>", "<w:rPr><w:b/></w:rPr><w:rPr/>")) }
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

    @Test
    fun aPlainTitleRemainsSeparateWhenCachedContentsStartInTheNextParagraph() {
        val title = "<w:r><w:rPr><w:b/></w:rPr><w:t>Contents title</w:t></w:r>"
        val begin = control("begin") + instruction("TOC \\o &quot;1-2&quot; \\h") + control("separate")
        val cached = "<w:r><w:t>Cached entry</w:t></w:r>"
        val isolatedOpening = normalize("<w:p>$title$begin</w:p><w:p>$cached${control("end")}</w:p>")
        assertEquals(2, Regex("<w:p>").findAll(isolatedOpening).count())
        val paragraphs = ConversionXml(GenerationContext()).read(isolatedOpening.toByteArray()).elements()
        assertEquals(listOf("r"), paragraphs[0].elements().map { it.name.localPart })
        assertEquals(listOf("fldSimple"), paragraphs[1].elements().map { it.name.localPart })
        assertTrue(isolatedOpening.contains("Contents title"))
        assertFalse(isolatedOpening.contains("fldChar"))
        val mixed = "<w:p>$title$begin$cached</w:p><w:p>$cached${control("end")}</w:p>"
        assertEquals("Contents must occupy standalone paragraphs",
            assertThrows(IllegalArgumentException::class.java) { normalize(mixed) }.message)
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
    @Test
    fun `a saved watermark keeps its original drawing nodes including an empty end marker`() {
        val paragraph = "<w:p><w:r><w:pict/></w:r></w:p>"
        val properties = """<w:sdtPr><w:id w:val="-12"/><w:docPartObj><w:docPartGallery w:val="Watermarks"/>
            <w:docPartUnique/></w:docPartObj></w:sdtPr>"""
        val content = "<w:sdt>$properties<w:sdtEndPr/><w:sdtContent>$paragraph</w:sdtContent></w:sdt>"
        val namespace = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val xml = ConversionXml(GenerationContext())
        fun read(value: String) = xml.read("<w:hdr xmlns:w=\"$namespace\">$value</w:hdr>".toByteArray())
        val root = read(content)
        val saved = root.elements().single().descendants().single { it.name.localPart == "p" }
        NativeDocxFields.normalize(root, ::fail)
        assertSame(saved, root.elements().single())
        for (invalid in listOf(content.replace("</w:sdtPr>", "<w:dataBinding/></w:sdtPr>"),
            content.replace("<w:sdtEndPr/>", "<w:sdtEndPr><w:rPr><w:b/></w:rPr></w:sdtEndPr>"),
            content.replace("<w:pict/>", "<w:t>Plain text</w:t>"))) {
            assertThrows(IllegalArgumentException::class.java) { NativeDocxFields.normalize(read(invalid), ::fail) }
        }
    }

    @Test
    fun `saved contents ignore only recognized control insertion and end mark formatting`() {
        val begin = control("begin") + instruction("TOC \\o &quot;1-2&quot; \\h") + control("separate")
        val cached = "<w:r><w:t>Cached entry</w:t></w:r>"
        val body = "<w:p>$begin$cached</w:p><w:p>$cached${control("end")}</w:p>"
        val gallery = """<w:id w:val="1"/><w:docPartObj><w:docPartGallery w:val="Table of Contents"/>
            <w:docPartUnique/></w:docPartObj>"""
        val formatting = """<w:rPr><w:rFonts w:ascii="Courier New"/><w:b w:val="0"/><w:color w:val="FF00FF"/>
            <w:sz w:val="144"/></w:rPr>"""
        fun wrapper(start: String, end: String) =
            "<w:sdt><w:sdtPr>$start$gallery</w:sdtPr>$end<w:sdtContent>$body</w:sdtContent></w:sdt>"
        assertEquals(normalize(wrapper("", "")), normalize(wrapper(formatting, "<w:sdtEndPr>$formatting</w:sdtEndPr>")))
        val active = formatting.replace("</w:rPr>", "<w:vanish/></w:rPr>")
        assertThrows(IllegalArgumentException::class.java) { normalize(wrapper(active, "")) }
        assertThrows(IllegalArgumentException::class.java) { normalize(wrapper("", "<w:sdtEndPr>$active</w:sdtEndPr>")) }
    }

    @Test
    fun `saved contents control distinguishes editing proxies from unknown font properties`() {
        val begin = control("begin") + instruction("TOC \\o &quot;1-2&quot; \\h") + control("separate")
        val cache = "<w:r><w:t>Old page reference</w:t></w:r>"
        val body = "<w:p>$begin$cache${control("end")}</w:p>"
        val gallery = """<w:docPartObj><w:docPartGallery w:val="Table of Contents"/><w:docPartUnique/></w:docPartObj>"""
        fun wrapper(open: String = "", end: String = "", contents: String = "<w:sdtContent>$body</w:sdtContent>") =
            "<w:sdt><w:sdtPr>$open$gallery</w:sdtPr>$end$contents</w:sdt>"
        val activeStyle = """<w:rPr><w:rFonts w:eastAsia="Liberation Sans" w:cstheme="majorBidi"/><w:bCs w:val="on"/>
            <w:szCs w:val="2"/><w:color w:val="auto" w:themeTint="AF" w:themeShade="0b"/></w:rPr>"""
        assertEquals(normalize(wrapper()), normalize(wrapper(activeStyle, "<w:sdtEndPr>$activeStyle</w:sdtEndPr>")))
        val rejected = listOf(
            "<w:rPr w:val=\"none\"/>", "<w:rPr>visible</w:rPr>",
            "<w:rPr><w:b/><w:b/></w:rPr>", "<w:rPr><x:b xmlns:x=\"urn:foreign\"/></w:rPr>",
            "<w:rPr><w:b>visible</w:b></w:rPr>", "<w:rPr><w:b><w:sz/></w:b></w:rPr>",
            "<w:rPr><w:b val=\"true\"/></w:rPr>", "<w:rPr><w:b w:extra=\"true\"/></w:rPr>",
            "<w:rPr><w:b w:val=\"bad\"/></w:rPr>", "<w:rPr><w:rFonts w:name=\"Real\"/></w:rPr>",
            "<w:rPr><w:rFonts w:ascii=\" \"/></w:rPr>", "<w:rPr><w:rFonts w:ascii=\"${"x".repeat(254)}\"/></w:rPr>",
            "<w:rPr><w:rFonts w:ascii=\"Normal&#x9;Font\"/></w:rPr>", "<w:rPr><w:szCs/></w:rPr>",
            "<w:rPr><w:szCs w:val=\"bad\"/></w:rPr>", "<w:rPr><w:szCs w:val=\"32768\"/></w:rPr>",
            "<w:rPr><w:color w:val=\"FFFF\"/></w:rPr>", "<w:rPr><w:color w:val=\"auto\" w:themeTint=\"nonhex\"/></w:rPr>",
            "<w:rPr><w:color w:val=\"auto\" w:other=\"none\"/></w:rPr>",
        )
        for (formatting in rejected) assertEquals("Unsupported contents control", assertThrows(IllegalArgumentException::class.java) {
            normalize(wrapper(formatting))
        }.message)
        for (end in listOf("<w:sdtEndPr w:val=\"1\"/>", "<w:sdtEndPr>text</w:sdtEndPr>",
            "<w:sdtEndPr><w:rPr/><w:rPr/></w:sdtEndPr>", "<w:sdtEndPr/><w:sdtEndPr/>")) {
            assertEquals("Unsupported contents control", assertThrows(IllegalArgumentException::class.java) {
                normalize(wrapper(end = end))
            }.message)
        }
        assertEquals("Missing contents control body", assertThrows(IllegalArgumentException::class.java) {
            normalize(wrapper(contents = ""))
        }.message)
        assertEquals("Contents control has no contents field", assertThrows(IllegalArgumentException::class.java) {
            normalize(wrapper(contents = "<w:sdtContent><w:p>$cache</w:p></w:sdtContent>"))
        }.message)
    }

}

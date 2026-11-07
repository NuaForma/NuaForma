package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordSettingsMetadataTest {
    private val context = GenerationContext()
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val word15 = "http://schemas.microsoft.com/office/word/2012/wordml"

    private fun xml(content: String) = ConversionXml(context).read(content.toByteArray())

    private fun normalize(content: String, document: String = "") = xml("""<w:settings xmlns:w="$word" xmlns:w15="$word15">$content</w:settings>""").also { root ->
        WordSettingsMetadata(context).normalize(mapOf("word/settings.xml" to root,
            "word/document.xml" to xml("""<w:document xmlns:w="$word">$document</w:document>"""))) { _, node ->
            NativeDocxReadException("Unsupported Word document settings", "word/settings.xml", location = "xml:${node.sourceLine}:${node.sourceColumn}")
        }
    }

    private fun compat(name: String, value: String = "1", extra: String = "") =
        """<w:compatSetting w:name="$name" w:uri="http://schemas.microsoft.com/office/word" w:val="$value" $extra/>"""

    @Test
    fun `named style pane filters are passive and validated individually`() {
        assertTrue(normalize("""<w:stylePaneFormatFilter w:allStyles="1" w:customStyles="false" w:top3HeadingStyles="on"/>""").elements().isEmpty())
        assertTrue(normalize("""<w:stylePaneFormatFilter w:val="3F01" w:allStyles="1"/>""").elements().isEmpty())
        assertThrows(NativeDocxReadException::class.java) { normalize("""<w:stylePaneFormatFilter w:allStyles="maybe"/>""") }
        assertThrows(NativeDocxReadException::class.java) { normalize("""<w:stylePaneFormatFilter w:invented="1"/>""") }
    }

    @Test fun `editing settings are excluded while effective tab stops and note settings survive`() {
        val content = """<w:embedSystemFonts/><w:doNotIncludeSubdocsInStats w:val="off"/><w:noPunctuationKerning w:val="true"/>
            <w:stylePaneFormatFilter w:val="3F01"/><w:documentProtection w:edit="forms" w:enforcement="1" w:formatting="false" w:hash="synthetic"/>
            <w15:chartTrackingRefBased/><w:defaultTabStop w:val="480"/><w:footnotePr/>
            <w:hdrShapeDefaults xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:v="urn:schemas-microsoft-com:vml"><o:shapedefaults v:ext="edit" spidmax="1026"/><o:shapelayout v:ext="edit"><o:idmap v:ext="edit" data="1"/></o:shapelayout></w:hdrShapeDefaults>"""
        val root = normalize(content)
        assertEquals(listOf("defaultTabStop", "footnotePr"), root.elements().map { it.name.localPart })
        assertEquals("480", root.elements().first().attribute(word, "val"))
        WordSettingsMetadata(context).normalize(emptyMap()) { _, _ -> error("No settings") }
    }

    @Test fun `recognized compatibility switches retain compatibility mode and do not remove unknown flags`() {
        for (value in listOf("1", "true", "on", "0", "false", "off")) {
            val flags = listOf("overrideTableStyleFontSizeAndJustification", "enableOpenTypeFeatures", "doNotFlipMirrorIndents", "differentiateMultirowTableHeaders")
                .joinToString("") { compat(it, value) }
            val root = normalize("<w:compat><w:doNotUseHTMLParagraphAutoSpacing/>$flags${compat("compatibilityMode", "15")}<w:unknown/></w:compat>")
            assertEquals(listOf("compatSetting", "unknown"), root.elements().single().elements().map { it.name.localPart })
            assertEquals("compatibilityMode", root.elements().single().elements().first().attribute(word, "name"))
        }
        assertTrue(normalize("<w15:unknown/>").elements().isNotEmpty())
        assertTrue(normalize("<w:compat><w15:unknown/></w:compat>").elements().single().elements().isNotEmpty())
    }

    @Test fun `table precedence and identifier-only header defaults survive while visible defaults still reject`() {
        assertTrue(normalize("<w:compat>${compat("overrideTableStyleFontSizeAndJustification", "0")}</w:compat>", "<w:tbl/>").elements().single().elements().isEmpty())
        assertTrue(normalize("<w:compat>${compat("overrideTableStyleFontSizeAndJustification")}</w:compat>", "<w:tbl/>").elements().single().elements().isEmpty())
        assertTrue(normalize("<w:hdrShapeDefaults/>", """<v:line xmlns:v="urn:schemas-microsoft-com:vml"/>""").elements().isEmpty())
        assertThrows(NativeDocxReadException::class.java) {
            normalize("""<w:hdrShapeDefaults xmlns:o="urn:schemas-microsoft-com:office:office"><o:shapedefaults fillcolor="red"/></w:hdrShapeDefaults>""")
        }
    }

    @Test fun `known setting names cannot disguise nested text attributes or invalid toggles`() {
        for (value in listOf("<w:embedSystemFonts w:val=\"invalid\"/>", "<w:noPunctuationKerning extra=\"1\"/>",
            "<w:noPunctuationKerning>text</w:noPunctuationKerning>", "<w:noPunctuationKerning><w:p/></w:noPunctuationKerning>",
            "<w:stylePaneFormatFilter w:val=\"xyz\"/>", "<w:stylePaneFormatFilter w15:val=\"3F01\"/>",
            "<w:documentProtection w:edit=\"invalid\"/>", "<w:documentProtection w:enforcement=\"invalid\"/>",
            "<w:documentProtection unexpected=\"1\"/>", "<w:hdrShapeDefaults unexpected=\"1\"/>",
            "<w:hdrShapeDefaults><w:p/></w:hdrShapeDefaults>", "<w15:chartTrackingRefBased w15:val=\"invalid\"/>")) {
            val error = assertThrows(NativeDocxReadException::class.java) { normalize(value) }
            assertEquals("word/settings.xml", error.part)
            assertTrue(error.location!!.startsWith("xml:"))
        }
    }

    @Test fun `compatibility entries reject duplicate names foreign attributes bad URIs and nested data`() {
        val valid = compat("enableOpenTypeFeatures")
        for (value in listOf(valid + valid, "<w:doNotUseHTMLParagraphAutoSpacing/><w:doNotUseHTMLParagraphAutoSpacing/>",
            compat("enableOpenTypeFeatures", "invalid"), compat("enableOpenTypeFeatures", extra = "extra=\"1\""),
            valid.replace(" w:val=\"1\"", ""),
            valid.replace("http://schemas.microsoft.com/office/word", "https://invalid.example/"),
            valid.replace("/>", "><w:p/></w:compatSetting>"), "<w:doNotUseHTMLParagraphAutoSpacing w:val=\"invalid\"/>"))
            assertThrows(NativeDocxReadException::class.java) { normalize("<w:compat>$value</w:compat>") }
    }

    @Test
    fun `identifier defaults accept allocated IDs with existing VML and reject malformed hierarchy or visible properties`() {
        val prefix = """<w:hdrShapeDefaults xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:v="urn:schemas-microsoft-com:vml">"""
        val defaults = """<o:shapedefaults v:ext="edit" spidmax="2050"/>"""
        val layout = """<o:shapelayout v:ext="edit"><o:idmap v:ext="edit" data="1,2,3"/></o:shapelayout>"""
        val vml = """<v:rect xmlns:v="urn:schemas-microsoft-com:vml"/>"""
        for (id in listOf("0", "1026", "2050", "8193", "2147483647")) {
            assertTrue(normalize(prefix + defaults.replace("2050", id) + layout + "</w:hdrShapeDefaults>", vml).elements().isEmpty())
        }
        for (bad in listOf(defaults + defaults, layout + layout, "<o:idmap data=\"1\"/>",
            defaults.replace("2050", "-1"), defaults.replace("2050", "2147483648"), defaults.replace("2050", "invalid"),
            defaults.replace("v:ext=\"edit\"", "ext=\"edit\""), defaults.replace("v:ext=\"edit\"", "v:ext=\"unknown\""),
            defaults.replace("spidmax=", "v:spidmax="), defaults.replace("/>", " style=\"width:40pt\"/>"),
            defaults.replace("/>", "><o:idmap/></o:shapedefaults>"), layout.replace("data=\"1,2,3\"", "data=\"1,1\""),
            layout.replace("data=\"1,2,3\"", "data=\"1,\""), layout.replace("data=\"1,2,3\"", "data=\"1,-2\""),
            layout.replace("data=\"1,2,3\"", "data=\"${"1,".repeat(400)}\""),
            layout.replace("data=\"1,2,3\"", "data=\"${(1..65).joinToString(",")}\""),
            layout.replace("<o:idmap", "<o:unknown"), "<o:shapelayout><o:shapedefaults/></o:shapelayout>",
            "<o:shapelayout><o:idmap/><o:idmap/></o:shapelayout>", "<o:shapedefaults>text</o:shapedefaults>")) {
            assertThrows(NativeDocxReadException::class.java, { normalize(prefix + bad + "</w:hdrShapeDefaults>", vml) }, bad)
        }
        assertThrows(NativeDocxReadException::class.java) { normalize("<w:hdrShapeDefaults/><w:hdrShapeDefaults/>") }
    }

    @Test fun `inactive section grids are removed without requiring a settings part while active grids reject`() {

        fun document(grid: String) = xml("""<w:document xmlns:w="$word"><w:body><w:sectPr><w:pgSz w:w="12240" w:h="15840"/>$grid</w:sectPr></w:body></w:document>""")

        fun apply(root: dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement) {
            WordSettingsMetadata(context).normalize(mapOf("word/document.xml" to root)) { part, _ -> NativeDocxReadException("Unsupported Word document settings", part) }
        }

        for (grid in listOf("<w:docGrid/>", "<w:docGrid w:linePitch=\"360\"/>", "<w:docGrid w:type=\"default\" w:charSpace=\"0\"/>")) {
            val root = document(grid)
            apply(root)
            assertEquals(listOf("pgSz"), root.descendants().single { it.name.localPart == "sectPr" }.elements().map { it.name.localPart })
        }
        for (grid in listOf("<w:docGrid w:type=\"lines\"/>", "<w:docGrid w:type=\"linesAndChars\"/>", "<w:docGrid w:type=\"snapToChars\"/>",
            "<w:docGrid w:linePitch=\"invalid\"/>", "<w:docGrid unexpected=\"1\"/>", "<w:docGrid/><w:docGrid/>")) {
            val error = assertThrows(NativeDocxReadException::class.java) { apply(document(grid)) }
            assertEquals("word/document.xml", error.part)
        }
    }

}

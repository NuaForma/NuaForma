package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordCompatibilityModeTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun setting(name: String = "compatibilityMode", value: String = "14") =
        """<w:compatSetting w:name="$name" w:uri="http://schemas.microsoft.com/office/word" w:val="$value"/>"""

    private fun root(content: String) = ConversionXml(GenerationContext()).read(
        """<w:settings xmlns:w="$word" xmlns:x="urn:unknown">$content<w:defaultTabStop w:val="720"/></w:settings>""".toByteArray())

    private fun reject(content: String) = assertThrows(NativeDocxReadException::class.java) {
        WordAuthoringMetadata.normalize(root(content), "word/settings.xml", false)
    }

    @Test
    fun `qualified mode values retain other effective settings in either script context`() {
        for (mode in listOf("12", "14", "15")) for (complex in listOf(false, true)) {
            val source = root("<w:compat>${setting(value = mode)}${setting("useWord2013TrackBottomHyphenation", "1")}</w:compat>")
            WordAuthoringMetadata.normalize(source, "word/settings.xml", complex)
            assertEquals(listOf("defaultTabStop"), source.elements().map { it.name.localPart })
            assertEquals("720", source.elements().single().attribute(word, "val"))
        }
    }

    @Test
    fun `source shape check preserves effective switches and leaves policy selection to the reader`() {
        val xml = ConversionXml(GenerationContext())
        for (mode in listOf("14", "16")) {
            val source = root("<w:compat>${setting(value = mode)}${setting("enableOpenTypeFeatures", "1")}<w:ulTrailSpace/><w:useFELayout w:val=\"0\"/></w:compat>")
            val original = xml.write(source)
            WordAuthoringMetadata.validateCompatibilitySource(source) { NativeDocxReadException("Invalid original shape", "word/settings.xml") }
            assertArrayEquals(original, xml.write(source))
        }
        for (bad in listOf("<w:compat>${setting().replace("/>", " extra=\"1\"/>")}</w:compat>",
            "<w:compat>${setting()}${setting(value = "15")}</w:compat>",
            "<w:compat>${setting()}</w:compat><w:compat/>"))
            assertThrows(NativeDocxReadException::class.java) {
                WordAuthoringMetadata.validateCompatibilitySource(root(bad)) { NativeDocxReadException("Invalid original shape", "word/settings.xml") }
            }
    }

    @Test
    fun `unqualified modes and unknown policies remain explicit failures`() {
        for (mode in listOf("0", "11", "13", "16", "014", " 14", "", "invalid"))
            reject("<w:compat>${setting(value = mode)}</w:compat>")
        reject("<w:compat>${setting("unknownPolicy", "1")}</w:compat>")
        reject("<w:compat>${setting("useWord2013TrackBottomHyphenation", "true")}</w:compat>")
    }

    @Test
    fun `foreign missing extra and active child attributes cannot hide in compatibility settings`() {
        val valid = setting()
        for (bad in listOf(valid.replace("w:name", "x:name"), valid.replace("w:val", "val"),
            valid.replace("w:uri", "x:uri"), valid.replace("schemas.microsoft.com", "unknown.example"),
            valid.replace("w:val=\"14\"", ""), valid.replace("/>", " extra=\"1\"/>"),
            valid.replace("/>", "><w:p/></w:compatSetting>"), valid.replace("/>", ">visible</w:compatSetting>"),
            valid.replace("w:compatSetting", "x:compatSetting"))) reject("<w:compat>$bad</w:compat>")
    }

    @Test
    fun `duplicate containers policies and nonblank container text reject before normalization`() {
        reject("<w:compat>${setting()}</w:compat><w:compat>${setting("useWord2013TrackBottomHyphenation", "0")}</w:compat>")
        reject("<w:compat>${setting()}${setting(value = "15")}</w:compat>")
        reject("<w:compat>${setting("useWord2013TrackBottomHyphenation", "0")}${setting("useWord2013TrackBottomHyphenation", "1")}</w:compat>")
        reject("<w:compat extra=\"1\">${setting()}</w:compat>")
        reject("<w:compat>visible${setting()}</w:compat>")
        val error = reject("<w:compat><x:unknown/></w:compat>")
        assertEquals("word/settings.xml", error.part)
    }

}

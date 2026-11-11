package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.CancellationToken
import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.contracts.GenerationLogging
import dev.nuaforma.contracts.LoggingMode
import dev.nuaforma.contracts.TemplateCleanupAction
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTextRun
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordTypographyPreferencesTest {
    private val word = PdfFixture.W

    private fun source(settings: String = "", style: String = "", properties: String = "", text: String = "Visible text"): ByteArray {
        val parts = PdfFixture.parts()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString()
            .replace("</w:style>", "$style</w:style>").toByteArray()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
            .replace("<w:p>", "<w:p><w:pPr>$properties</w:pPr>").replace("Hello {name}", text).toByteArray()
        parts["word/settings.xml"] = "<w:settings xmlns:w=\"$word\">$settings</w:settings>".toByteArray()
        parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").decodeToString()
            .replace("</Relationships>", """<Relationship Id="settings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/></Relationships>""").toByteArray()
        parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString()
            .replace("</Types>", """<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>""").toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun read(bytes: ByteArray) = NativeDocxReader(GenerationContext()) { _, _, _ -> "regular" }.read(bytes)

    private fun policy(settings: String) = (read(source(settings)).blocks.single() as PdfParagraph).underlineTrailingSpaces

    @Test
    fun `trailing underline policy accepts on off values preserves absence and rejects malformed duplicates`() {
        assertNull(policy(""))
        for ((value, expected) in listOf("" to true, " w:val=\"1\"" to true, " w:val=\"on\"" to true,
            " w:val=\"false\"" to false, " w:val=\"off\"" to false, " w:val=\"0\"" to false)) {
            assertEquals(expected, policy("<w:compat><w:ulTrailSpace$value/></w:compat>"))
        }
        for (bad in listOf("<w:ulTrailSpace w:val=\"bad\"/>", "<w:ulTrailSpace val=\"1\"/>",
            "<w:ulTrailSpace/><w:ulTrailSpace/>", "<w:ulTrailSpace>text</w:ulTrailSpace>", "<w:ulTrailSpace><w:p/></w:ulTrailSpace>")) {
            val failure = assertThrows(NativeDocxReadException::class.java) { policy("<w:compat>$bad</w:compat>") }
            assertEquals("word/settings.xml", failure.part)
        }
    }

    @Test
    fun `paragraph overrides disable inherited punctuation overflow and suppression gates active hyphenation`() {
        val inherited = "<w:pPr><w:overflowPunct/><w:suppressAutoHyphens/></w:pPr>"
        assertDoesNotThrow { read(source("<w:autoHyphenation/>", inherited, "<w:overflowPunct w:val=\"0\"/>")) }
        val overflow = assertThrows(NativeDocxReadException::class.java) { read(source(style = inherited)) }
        assertEquals("word/styles.xml", overflow.part)
        assertThrows(NativeDocxReadException::class.java) { read(source("<w:autoHyphenation/>", inherited,
            "<w:overflowPunct w:val=\"0\"/><w:suppressAutoHyphens w:val=\"0\"/>")) }
        for (value in listOf("0", "1", "true", "false", "on", "off")) assertDoesNotThrow {
            read(source("<w:autoHyphenation w:val=\"0\"/><w:doNotHyphenateCaps/>", properties = "<w:suppressAutoHyphens w:val=\"$value\"/>"))
        }
        for (bad in listOf("<w:overflowPunct w:val=\"invalid\"/>", "<w:suppressAutoHyphens w:val=\"invalid\"/>",
            "<w:overflowPunct/><w:overflowPunct/>", "<w:suppressAutoHyphens>text</w:suppressAutoHyphens>")) {
            assertThrows(NativeDocxReadException::class.java) { read(source(properties = bad)) }
        }
    }

    @Test
    fun `editor style updates retain explicit formatting but reject malformed metadata`() {
        for (value in listOf("", " w:val=\"0\"", " w:val=\"1\"", " w:val=\"false\"")) {
            assertDoesNotThrow { read(source(style = "<w:autoRedefine$value/>")) }
        }
        for (bad in listOf("<w:autoRedefine w:val=\"bad\"/>", "<w:autoRedefine/><w:autoRedefine/>",
            "<w:autoRedefine><w:rPr/></w:autoRedefine>", "<w:autoRedefine val=\"1\"/>", "<w:autoRedefine>text</w:autoRedefine>")) {
            val failure = assertThrows(NativeDocxReadException::class.java) { read(source(style = bad)) }
            assertEquals("word/styles.xml", failure.part)
        }
    }

    @Test
    fun `grammar preferences remain inert and accept legacy locale spellings without fetching a grammar provider`() {
        val preference = """<w:activeWritingStyle w:lang="es-ES_tradnl" w:vendorID="64" w:dllVersion="4096" w:nlCheck="1" w:checkStyle="0" w:appName="MSWord"/>"""
        assertDoesNotThrow { read(source(preference)) }
        assertDoesNotThrow { read(source(preference + preference.replace("es-ES_tradnl", "nl-NL"))) }
        val bounded = (1..64).joinToString("") { preference.replace("es-ES_tradnl", "lang-$it") }
        assertDoesNotThrow { read(source(bounded)) }
        assertThrows(NativeDocxReadException::class.java) { read(source(bounded + preference)) }
        for (bad in listOf(preference + preference, preference.replace("w:nlCheck=\"1\"", "w:nlCheck=\"maybe\""),
            preference.replace("w:lang=\"es-ES_tradnl\"", "w:lang=\"../bad\""),
            preference.replace("/>", ">text</w:activeWritingStyle>"), preference.replace(" w:lang=", " unexpected=\"1\" w:lang="))) {
            assertThrows(NativeDocxReadException::class.java) { read(source(bad)) }
        }
    }

    @Test
    fun `Far East layout remains guarded for complex scripts and validates source switches`() {
        assertDoesNotThrow { read(source("<w:compat><w:useFELayout/></w:compat>")) }
        assertDoesNotThrow { read(source("<w:compat><w:useFELayout/></w:compat>", text = "Latin Ελληνικά Кириллица")) }
        for (bad in listOf("<w:useFELayout w:val=\"bad\"/>", "<w:useFELayout/><w:useFELayout/>"))
            assertThrows(NativeDocxReadException::class.java) { read(source("<w:compat>$bad</w:compat>")) }
        val context = GenerationContext()
        val xml = ConversionXml(context)
        val settings = xml.read("<w:settings xmlns:w=\"$word\"><w:compat><w:useFELayout/></w:compat></w:settings>".toByteArray())
        val document = xml.read("<w:document xmlns:w=\"$word\"><w:body><w:p><w:r><w:t>中文</w:t></w:r></w:p></w:body></w:document>".toByteArray())
        assertThrows(NativeDocxReadException::class.java) {
            WordSettingsMetadata(context).normalize(mapOf("word/settings.xml" to settings, "word/document.xml" to document)) { part, _ -> NativeDocxReadException("Unsupported settings", part) }
        }
    }

    @Test
    fun `ligature preferences cascade without enabling discretionary or historical features`() {
        val namespace = "http://schemas.microsoft.com/office/word/2010/wordml"
        val settings = """<w:compat><w:compatSetting w:name="enableOpenTypeFeatures" w:uri="http://schemas.microsoft.com/office/word" w:val="1"/></w:compat>"""

        fun feature(value: String) = """<w:rPr xmlns:w14="$namespace"><w14:ligatures w14:val="$value"/></w:rPr>"""

        for ((value, enabled) in listOf("none" to false, "standard" to true, "standardContextual" to true)) {
            val paragraph = read(source(settings, style = feature(value))).blocks.single() as PdfParagraph
            assertEquals(enabled, (paragraph.runs.single() as PdfTextRun).style.fontFeatures.standardLigatures)
        }
        for (value in listOf("all", "historical", "discretionary", "contextual", "unknown", "")) {
            assertThrows(NativeDocxReadException::class.java) { read(source(settings, style = feature(value))) }
        }
    }

    @Test
    fun `settings order cannot lose active hyphenation or the underline policy`() {
        val flags = "<w:compat><w:ulTrailSpace/></w:compat>"
        for (settings in listOf(flags + "<w:autoHyphenation/>", "<w:autoHyphenation/>" + flags)) {
            val paragraph = read(source(settings, properties = "<w:suppressAutoHyphens/>")).blocks.single() as PdfParagraph
            assertEquals(true, paragraph.underlineTrailingSpaces)
            val failure = assertThrows(NativeDocxReadException::class.java) { read(source(settings)) }
            assertEquals("word/settings.xml", failure.part)
        }
    }

    @Test
    fun `settings normalization observes cancellation before consuming preferences`() {
        val xml = ConversionXml(GenerationContext())
        val settings = xml.read("<w:settings xmlns:w=\"$word\"><w:compat><w:ulTrailSpace/></w:compat></w:settings>".toByteArray())
        val cancelled = GenerationContext(cancellation = CancellationToken { true })
        assertThrows(GenerationException::class.java) {
            WordSettingsMetadata(cancelled).normalize(mapOf("word/settings.xml" to settings)) { part, _ -> NativeDocxReadException("Unexpected source failure", part) }
        }
        assertEquals(1, settings.elements().size)
    }

    @Test
    fun `an unused punctuation overflow style does not activate its geometry`() {
        val parts = PdfFixture.unzip(source()).toMutableMap()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("</w:styles>",
            """<w:style w:type="paragraph" w:styleId="Unused"><w:name w:val="Unused"/><w:pPr><w:overflowPunct/></w:pPr></w:style></w:styles>""").toByteArray()
        assertDoesNotThrow { read(PdfFixture.zip(parts)) }
    }

    @Test
    fun `cleanup removes unqualified active preferences without deleting paragraph text or evaluating markers`() {
        val input = source("<w:autoHyphenation/>", "<w:pPr><w:overflowPunct/></w:pPr>", text = "Keep {name}")
        val (cleaned, report) = NativeTemplateCleanup(GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).clean(input)
        val before = PdfFixture.unzip(input)
        val after = PdfFixture.unzip(cleaned)
        assertArrayEquals(before.getValue("word/document.xml"), after.getValue("word/document.xml"))
        assertEquals(2, report.changes.size)
        assertEquals(setOf("word/styles.xml", "word/settings.xml"), report.changes.map { it.part }.toSet())
        assertTrue(report.changes.all { it.action == TemplateCleanupAction.REMOVE_ELEMENT })
        assertDoesNotThrow { read(cleaned) }
    }

}

package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeWordValuesTest {
    private val context = GenerationContext()
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private fun instruction(code: String) = NativeDocxFields.instruction(code, ::fail)

    private fun root(content: String) = ConversionXml(context).read("""<w:body xmlns:w="$word">$content</w:body>""".toByteArray())

    @Test
    fun `document properties use actual package strings with case-insensitive lookup and no cache fallback`() {
        val values = NativeWordValues(context, mapOf("Report Title" to WordPackageMetadata.Property("lpwstr", "Actual title")), null, {}, ::fail)
        assertEquals("Actual title", values.resolve(instruction("DOCPROPERTY \"report title\" \\* MERGEFORMAT")))
        assertThrows(IllegalArgumentException::class.java) { values.resolve(instruction("DOCPROPERTY Missing")) }
        assertNull(values.resolve(instruction("PAGE")))
        for (properties in listOf(mapOf("A" to WordPackageMetadata.Property("i4", "12")),
            mapOf("A" to WordPackageMetadata.Property("lpwstr", "first"), "a" to WordPackageMetadata.Property("lpwstr", "second")),
            mapOf("A" to WordPackageMetadata.Property("lpwstr", "x".repeat(16_385))),
            mapOf("A" to WordPackageMetadata.Property("lpwstr", "line\nbreak")))) {
            val invalid = NativeWordValues(context, properties, null, {}, ::fail)
            assertThrows(IllegalArgumentException::class.java) { invalid.resolve(instruction("DOCPROPERTY A")) }
        }
    }

    @Test
    fun `textual core properties are read from the qualified namespace and duplicate values reject`() {
        val core = ConversionXml(context).read("""<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Actual core title</dc:title><dc:creator>Example Author</dc:creator></cp:coreProperties>""".toByteArray())
        val values = NativeWordValues(context, emptyMap(), core, {}, ::fail)
        assertEquals("Actual core title", values.resolve(instruction("DOCPROPERTY TITLE")))
        assertEquals("Example Author", values.resolve(instruction("DOCPROPERTY Author")))
        core.children += core.elements().first()
        assertThrows(IllegalArgumentException::class.java) { values.resolve(instruction("DOCPROPERTY Title")) }
    }

    @Test
    fun `repeated references count toward the expanded static text budget`() {
        val values = NativeWordValues(context, mapOf("A" to WordPackageMetadata.Property("lpwstr", "x".repeat(16_384))), null, {}, ::fail)
        repeat(61) { assertEquals(16_384, values.resolve(instruction("DOCPROPERTY A"))!!.length) }
        assertThrows(IllegalArgumentException::class.java) { values.resolve(instruction("DOCPROPERTY A")) }
    }

    @Test
    fun `nonprinting properties styles and settings cannot define or activate Word fields`() {
        val ignored = listOf("styles", "settings").map { name -> ConversionXml(context).read(
            """<w:$name xmlns:w="$word"><w:fldSimple w:instr='SET Choice "metadata"'/><w:fldSimple w:instr="DDEAUTO server"/></w:$name>""".toByteArray()) } +
            ConversionXml(context).read("""<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:w="$word"><w:fldSimple w:instr="UNKNOWN"/></cp:coreProperties>""".toByteArray())
        val values = NativeWordValues(context, emptyMap(), null, {}, ::fail)
        values.index(ignored + root("""<w:p><w:fldSimple w:instr='SET Choice "visible"'/></w:p>"""))
        assertEquals("visible", values.resolve(instruction("REF Choice")))
    }

    @Test
    fun `literal SET assignments render nothing and REF reads the definition rather than stale result text`() {
        val values = NativeWordValues(context, emptyMap(), null, {}, ::fail)
        values.index(listOf(root("""<w:p><w:fldSimple w:instr='SET Gender "M"'><w:r><w:t>stale</w:t></w:r></w:fldSimple></w:p>""")))
        assertEquals("", values.resolve(instruction("SET Gender \"M\"")))
        assertEquals("M", values.resolve(instruction("REF Gender")))
        assertThrows(IllegalArgumentException::class.java) { values.resolve(instruction("REF Gender \\h")) }
        assertThrows(IllegalArgumentException::class.java) { values.resolve(instruction("PAGEREF Gender")) }
        for (body in listOf("""<w:fldSimple w:instr='SET X "one"'/><w:fldSimple w:instr='SET X "two"'/>""",
            """<w:bookmarkStart w:id="1" w:name="X"/><w:fldSimple w:instr='SET X "one"'/>""")) {
            assertThrows(IllegalArgumentException::class.java) { NativeWordValues(context, emptyMap(), null, {}, ::fail).index(listOf(root(body))) }
        }
    }

    @Test
    fun `static field grammar rejects calculations dynamic switches and malformed arguments`() {
        assertEquals("42", instruction("SET Number 42").literal)
        assertEquals("", instruction("SET Empty \"\"").literal)
        assertEquals("FORMCHECKBOX", instruction("FORMCHECKBOX").kind)
        for (code in listOf("SET X OtherBookmark", "SET X =1+2", "SET X", "DOCPROPERTY", "DOCPROPERTY \"\"",
            "DOCPROPERTY Title \\@ yyyy", "DOCPROPERTY Title \\* ARABIC", "FORMCHECKBOX extra", "SET X \"M\" extra")) {
            assertThrows(IllegalArgumentException::class.java, { instruction(code) }, code)
        }
    }

}

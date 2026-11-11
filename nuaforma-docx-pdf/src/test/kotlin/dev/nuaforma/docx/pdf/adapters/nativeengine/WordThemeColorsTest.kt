package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.pdf.PdfColor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordThemeColorsTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val drawing = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val xml = ConversionXml(GenerationContext())
    private val palette = linkedMapOf("dk1" to "000000", "lt1" to "FFFFFF", "dk2" to "204060", "lt2" to "FFEEDD",
        "accent1" to "156082", "accent2" to "C0504D", "accent3" to "4F81BD", "accent4" to "70AD47",
        "accent5" to "ED7D31", "accent6" to "A5A5A5", "hlink" to "0000FF", "folHlink" to "800080")

    private fun root(content: String) = xml.read(content.toByteArray())

    private fun theme(entries: String = palette.entries.joinToString("") { (name, value) -> "<a:$name><a:srgbClr val=\"$value\"/></a:$name>" }) =
        root("<a:theme xmlns:a=\"$drawing\"><a:themeElements><a:clrScheme name=\"Qualified\">$entries</a:clrScheme></a:themeElements></a:theme>")

    private fun color(properties: String) = root("<w:color xmlns:w=\"$word\" xmlns:x=\"urn:unknown\" $properties/>")

    private fun colors(theme: dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement? = theme()) = WordThemeColors(theme) { _, message ->
        throw NativeDocxReadException(message, "word/theme/theme1.xml")
    }

    private fun rgb(value: String) = PdfColor(value.substring(0, 2).toInt(16), value.substring(2, 4).toInt(16), value.substring(4, 6).toInt(16))

    private fun close(expected: String, actual: PdfColor) {
        val reference = rgb(expected)
        assertTrue(kotlin.math.abs(reference.red - actual.red) <= 1 && kotlin.math.abs(reference.green - actual.green) <= 1 &&
            kotlin.math.abs(reference.blue - actual.blue) <= 1, "$expected versus $actual")
    }

    @Test
    fun `all qualified roles resolve the palette rather than a stale RGB cache`() {
        val colors = colors()
        val aliases = mapOf("text1" to "dk1", "background1" to "lt1", "text2" to "dk2", "background2" to "lt2",
            "dark1" to "dk1", "light1" to "lt1", "dark2" to "dk2", "light2" to "lt2", "hyperlink" to "hlink", "followedHyperlink" to "folHlink") +
            (1..6).associate { "accent$it" to "accent$it" }
        for ((role, entry) in aliases) for (cache in listOf("", "w:val=\"FF00FF\"", "w:val=\"auto\""))
            assertEquals(rgb(palette.getValue(entry)), colors.resolve(color("w:themeColor=\"$role\" $cache")))
        assertEquals(rgb("123456"), colors.resolve(color("w:val=\"123456\"")))
        assertEquals(rgb("123456"), colors.resolve(color("w:val=\"123456\" w:themeColor=\"none\"")))
    }

    @Test
    fun `Word HSL examples tint precedence and endpoint identities are bounded`() {
        val colors = colors()
        close("943634", colors.resolve(color("w:themeColor=\"accent2\" w:themeShade=\"BF\"")))
        close("95B3D7", colors.resolve(color("w:themeColor=\"accent3\" w:themeTint=\"99\"")))
        close("45B0E1", colors.resolve(color("w:themeColor=\"accent1\" w:themeTint=\"99\" w:themeShade=\"BF\"")))
        for (role in palette.keys.filter { it.startsWith("accent") }) {
            for (name in listOf("themeTint", "themeShade"))
                assertEquals(rgb(palette.getValue(role)), colors.resolve(color("w:themeColor=\"$role\" w:$name=\"FF\"")))
            assertEquals(rgb("FFFFFF"), colors.resolve(color("w:themeColor=\"$role\" w:themeTint=\"00\"")))
            assertEquals(rgb("000000"), colors.resolve(color("w:themeColor=\"$role\" w:themeShade=\"00\"")))
        }
    }

    @Test
    fun `malformed or unresolved run colors reject instead of using a cache`() {
        val colors = colors()
        for (properties in listOf("w:themeColor=\"accent7\" w:val=\"123456\"", "w:themeColor=\"accent1\" x:val=\"123456\"",
            "w:themeColor=\"accent1\" w:themeTint=\"1\"", "w:themeColor=\"accent1\" w:themeTint=\"GG\"",
            "w:themeColor=\"accent1\" w:themeShade=\"-1\"", "w:themeTint=\"99\" w:val=\"123456\"",
            "w:themeColor=\"none\" w:themeTint=\"99\" w:val=\"123456\"", "w:val=\"auto\"", "w:val=\"12345\""))
            assertThrows(NativeDocxReadException::class.java) { colors.resolve(color(properties)) }
        assertThrows(NativeDocxReadException::class.java) { colors(null).resolve(color("w:themeColor=\"accent1\" w:val=\"123456\"")) }
    }

    @Test
    fun `missing duplicate transformed and nonstandard system palette entries reject`() {
        for (entries in listOf("", "<a:accent1><a:srgbClr val=\"123456\"/></a:accent1>".repeat(2),
            "<a:accent1><a:srgbClr val=\"123456\"><a:alpha val=\"50000\"/></a:srgbClr></a:accent1>",
            "<a:accent1><a:schemeClr val=\"accent2\"/></a:accent1>",
            "<a:accent1><a:sysClr val=\"windowText\" lastClr=\"123456\"/></a:accent1>"))
            assertThrows(NativeDocxReadException::class.java) { colors(theme(entries)).resolve(color("w:themeColor=\"accent1\"")) }
        val system = colors(theme("<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>"))
        assertEquals(rgb("000000"), system.resolve(color("w:themeColor=\"text1\"")))
        assertEquals(rgb("FFFFFF"), system.resolve(color("w:themeColor=\"background1\"")))
    }

    @Test
    fun `original source validation is read only and rejects duplicate themed colors`() {
        val node = root("<w:rPr xmlns:w=\"$word\"><w:color w:themeColor=\"accent1\" w:val=\"FF00FF\"/></w:rPr>")
        val original = xml.write(node)
        WordThemeColors.validateSource(node) { _, message -> throw NativeDocxReadException(message, "word/styles.xml") }
        assertArrayEquals(original, xml.write(node))
        val palette = theme()
        val originalPalette = xml.write(palette)
        WordThemeColors.validateSource(palette) { _, message -> throw NativeDocxReadException(message, "word/theme/theme1.xml") }
        assertArrayEquals(originalPalette, xml.write(palette))
        for (broken in listOf(theme(""), theme("<a:accent1><a:srgbClr val=\"123456\"/></a:accent1>".repeat(12))))
            assertThrows(NativeDocxReadException::class.java) {
                WordThemeColors.validateSource(broken) { _, message -> throw NativeDocxReadException(message, "word/theme/theme1.xml") }
            }
        for (content in listOf("<w:color w:themeColor=\"accent1\" extra=\"1\"/>",
            "<w:color w:themeColor=\"accent1\"><w:b/></w:color>", "<w:color w:themeColor=\"accent1\"/>".repeat(2)))
            assertThrows(NativeDocxReadException::class.java) {
                WordThemeColors.validateSource(root("<w:rPr xmlns:w=\"$word\">$content</w:rPr>")) { _, message ->
                    throw NativeDocxReadException(message, "word/styles.xml")
                }
            }
    }

    @Test
    fun `independent palettes retain their own colors across interleaved resolution`() {
        val first = colors()
        val second = colors(theme("<a:accent1><a:srgbClr val=\"663399\"/></a:accent1>"))
        repeat(20) {
            assertEquals(rgb("156082"), first.resolve(color("w:themeColor=\"accent1\"")))
            assertEquals(rgb("663399"), second.resolve(color("w:themeColor=\"accent1\"")))
        }
    }

    private fun fill(properties: String) = root("""<w:shd xmlns:w="$word" xmlns:x="urn:unknown" w:val="clear" w:color="auto" $properties/>""")

    @Test
    fun `cell fills prefer the actual theme and Word none removes the fill instead of using its cache`() {
        val selected = theme(palette.entries.joinToString("") { (name, value) ->
            "<a:$name><a:srgbClr val=\"${if (name == "dk2") "1F497D" else value}\"/></a:$name>"
        })
        val colors = colors(selected)
        for ((shade, expected) in listOf("D9" to "D9D9D9", "A6" to "A6A6A6", "F2" to "F2F2F2")) {
            assertEquals(rgb(expected), colors.cellFill(fill("w:fill=\"FF0000\" w:themeFill=\"background1\" w:themeFillShade=\"$shade\"")))
        }
        assertEquals(rgb("8DB3E2"), colors.cellFill(fill("w:fill=\"FF0000\" w:themeFill=\"text2\" w:themeFillTint=\"66\"")))
        assertNull(colors.cellFill(fill("w:fill=\"336699\" w:themeFill=\"none\"")))
        assertEquals(rgb("336699"), colors.resolve(color("w:val=\"336699\" w:themeColor=\"none\"")))
        assertEquals(rgb("123456"), colors.cellFill(fill("w:fill=\"123456\"")))
        assertNull(colors.cellFill(fill("w:fill=\"auto\"")))
    }

    @Test
    fun `original cell theme attributes and missing palettes reject explicitly`() {
        for (properties in listOf("w:themeFill=\"unknown\"", "w:themeFill=\"background1\" extra=\"1\"",
            "x:themeFill=\"background1\"", "w:themeFill=\"background1\" w:themeFillShade=\"GG\"",
            "w:themeFill=\"none\" w:themeFillTint=\"99\"", "w:themeFillTint=\"99\"",
            "w:themeFill=\"background1\" w:fill=\"invalid\"")) {
            val element = fill(properties)
            assertThrows(NativeDocxReadException::class.java) { colors().cellFill(element) }
            val propertiesRoot = root("""<w:tcPr xmlns:w="$word" xmlns:x="urn:unknown"><w:shd w:val="clear" w:color="auto" $properties/></w:tcPr>""")
            assertThrows(NativeDocxReadException::class.java) {
                WordThemeColors.validateSource(propertiesRoot) { _, message -> throw NativeDocxReadException(message, "word/styles.xml") }
            }
        }
        assertThrows(NativeDocxReadException::class.java) { colors(null).cellFill(fill("w:themeFill=\"accent1\"")) }
        val duplicates = root("""<w:tcPr xmlns:w="$word"><w:shd w:val="clear" w:color="auto" w:themeFill="background1"/><w:shd w:val="clear" w:color="auto" w:fill="123456"/></w:tcPr>""")
        assertThrows(NativeDocxReadException::class.java) {
            WordThemeColors.validateSource(duplicates) { _, message -> throw NativeDocxReadException(message, "word/styles.xml") }
        }
    }

}

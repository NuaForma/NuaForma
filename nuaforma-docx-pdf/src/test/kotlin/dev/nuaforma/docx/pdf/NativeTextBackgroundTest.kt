package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeTextBackgroundTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `highlights shading inheritance and automatic text colors match actual Word`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for ((variant, count) in listOf("metrics" to 7, "highlight" to 31, "shading" to 20, "contrast" to 131,
            "highlight-contrast" to 59)) {
            val profiles = if (paper == "a4" && variant in setOf("highlight", "shading"))
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            val markers = Regex("[AHMS][0-9]{2}_(?:TEXT|COLOR|INHERITED|RESET|CHARACTER|REVEAL|TAB|END|BREAK|AFTER|WRAP|TRAILING|UNICODE|JUSTIFY|SPACE|NIL|CLEAR|PRECEDENCE|WHITE|MARK|SMALL|LARGE|DEFAULT|AUTO|BLACK|HEADER|FOOTER)")
            support.verify("/text-backgrounds/$variant/$paper/", profiles, markers, count,
                foregroundColors = true)
        }
    }

    @Test
    fun `unsupported patterns malformed declarations and duplicate original backgrounds publish no PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        for ((folder, name, before, after) in listOf(
            listOf("highlight", "highlight", "w:val=\"yellow\"", "w:val=\"beige\""),
            listOf("highlight", "highlight", "<w:highlight w:val=\"yellow\"/>", "<w:highlight w:val=\"yellow\"/><w:highlight w:val=\"none\"/>"),
            listOf("shading", "shd", "w:val=\"clear\"", "w:val=\"pct10\""),
            listOf("shading", "shd", "w:fill=\"FFFFFF\"", "w:fill=\"not-a-color\""))) {
            support.rejectChanged("/text-backgrounds/$folder/a4/source.docx", "word/document.xml", name) { xml ->
                xml.replaceFirst(before, after)
            }
        }
    }

}

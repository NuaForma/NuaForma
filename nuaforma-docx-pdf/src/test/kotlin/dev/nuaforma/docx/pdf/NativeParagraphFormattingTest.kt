package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeParagraphFormattingTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `paragraph backgrounds and tight line spacing retain Word layout and exact native golden pages`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val pattern = Regex("[BCIJPRST][0-9]{2}_(?:FIRST|SECOND|LINE|SMALL|LARGE|NEXT|INHERITED|RESET|BEFORE|AFTER|FILL|LEFT|HANG|WRAP|GROUP|END|RUN|MARK|CLEAR|NIL|EMPTY|LIGHT|DARK|AUTO|BLACK|WHITE|DIFFERENT|PLAIN|BORDER|CELL|BAND|SEPARATOR)")
        val cases = listOf("tight" to 27, "backgrounds" to 14, "inheritance" to 14, "integration" to 8, "zero-margins" to 8,
            "pagination" to 90, "split-background" to 100, "joins" to 21) + listOf(120, 213, 220, 228, 240).map { "ratio-$it" to 2 }
        for ((variant, count) in cases) {
            val profiles = if (paper == "a4" && variant in setOf("tight", "backgrounds", "integration", "zero-margins", "split-background", "joins"))
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/paragraph-formatting/$variant/$paper/", profiles, pattern, count,
                pageCount = if (variant in setOf("pagination", "split-background")) 2 else 1, foregroundColors = true,
                logicalTextOrder = variant in setOf("tight", "ratio-120"))
        }
    }

    @Test
    fun `malformed patterned or duplicate paragraph shading and invalid line multiples publish no PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        for ((before, after) in listOf("w:val=\"clear\"" to "w:val=\"pct10\"",
            "w:fill=\"DDEEFF\"" to "w:fill=\"invalid\"",
            "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"DDEEFF\"/>" to
                "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"DDEEFF\"/><w:shd w:val=\"nil\"/>")) {
            support.rejectChanged("/paragraph-formatting/backgrounds/a4/source.docx", "word/document.xml", "shd") {
                it.replaceFirst(before, after)
            }
        }
        for (bad in listOf("0", "-1", "2401", "invalid")) {
            support.rejectChanged("/paragraph-formatting/ratio-220/a4/source.docx", "word/document.xml", "spacing") {
                it.replaceFirst("w:line=\"220\"", "w:line=\"$bad\"")
            }
        }
    }

}

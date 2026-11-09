package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeCellMarginsTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `direct inherited zero and merged cell margins retain actual Word geometry`(paper: String) {
        NativeWordFeatureSupport(temporary).verify("/cell-margins/$paper/",
            if (paper == "a4") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard"),
            Regex("M[01][0-3][01]A"), 14)
    }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `minimum row heights include the effective Word cell frame`(paper: String) {
        NativeWordFeatureSupport(temporary).verify("/minimum-cell-margins/$paper/", listOf("standard"),
            Regex("M[01][0-3][01]A"), 14)
    }

    @Test
    fun `malformed original cell margins report their source and publish no partial PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        for (replacement in listOf("<w:bottom/>", "<w:bottom w:w=\"-1\" w:type=\"dxa\"/>",
            "<w:bottom w:w=\"288001\" w:type=\"dxa\"/>", "<w:bottom w:w=\"142\" w:type=\"pct\"/>",
            "<w:bottom w:w=\"1\" w:type=\"nil\"/>", "<w:bottom w:type=\"nil\"/>",
            "<w:bottom w:w=\"142\" w:type=\"dxa\" extra=\"1\"/>",
            "<w:bottom w:w=\"142\" w:type=\"dxa\"/><w:bottom w:w=\"1\" w:type=\"dxa\"/>")) {
            support.rejectChanged("/cell-margins/a4/source.docx", "word/document.xml", "bottom") {
                it.replaceFirst("<w:bottom w:w=\"142\" w:type=\"dxa\"/>", replacement)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `fractional cell twips retain the independently exported whole twip Word geometry`(paper: String) {
        NativeWordFeatureSupport(temporary).verify("/fractional-cell-margins/$paper/", listOf("standard"),
            Regex("M[01][0-3][01]A"), 14, golden = "/cell-margins/$paper/page-1.png")
    }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `nil margins restore table defaults past style overrides with exact physical controls`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        support.verify("/nil-cell-margins/$paper/",
            if (paper == "a4") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard"),
            Regex("M[01][0-3][01]A"), 14)
        support.verify("/nil-cell-margins-control/$paper/", listOf("standard"), Regex("M[01][0-3][01]A"), 14,
            golden = "/nil-cell-margins/$paper/page-1.png")
    }

}

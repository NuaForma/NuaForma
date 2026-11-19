package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeTableHorizontalAlignmentTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `malformed original table alignment cannot become a valid typed default`() {
        val support = NativeWordFeatureSupport(temporary)
        for (replacement in listOf("<w:jc w:val=\"both\"/>", "<w:jc/>", "<w:jc val=\"center\"/>",
            "<w:jc w:val=\"center\" extra=\"1\"/>", "<w:jc w:val=\"center\">text</w:jc>",
            "<w:jc w:val=\"center\"/><w:jc w:val=\"center\"/>")) {
            support.rejectChanged("/table-alignment/fixed/mode15-center/a4/source.docx", "word/document.xml", "jc") {
                it.replaceFirst(Regex("<w:jc\\b[^<>]*/>"), replacement)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `fixed and negotiated table widths retain actual Word content under horizontal alignment`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (kind in listOf("fixed", "auto")) {
            val variants = if (kind == "fixed") listOf("mode12-omitted", "mode12-left", "mode12-center", "mode12-right",
                "mode15-omitted", "mode15-left", "mode15-center", "mode15-right") else listOf("omitted", "left", "center", "right")
            for (variant in variants) {
                val resource = "/table-alignment/$kind/$variant/$paper/"
                val representative = paper == "a4" && (kind == "fixed" && variant in setOf("mode12-right", "mode15-center") ||
                    kind == "auto" && variant in setOf("center", "right"))
                val profiles = if (representative) listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
                val pattern = if (kind == "fixed") Regex("A00_BODY|A99_NEXT|T[0-2][01]_CELL") else
                    Regex("(?:PHYSICAL|PERCENT|IMPLICIT|MERGED)_[1-3]_[12]|MERGED_SHARED_COLUMNS|END_PREFERRED_TABLES")
                support.verify(resource, profiles, pattern, if (kind == "fixed") 8 else 26)
            }
        }
    }

    @Test
    fun `unqualified legacy custom-border alignment identifies the table property instead of its last text run`() {
        NativeWordFeatureSupport(temporary).rejectChanged("/table-alignment/fixed/mode12-right/a4/source.docx", "word/document.xml", "jc") {
            it.replaceFirst("<w:tcPr>", "<w:tcPr><w:tcBorders><w:right w:val=\"single\" w:sz=\"4\" w:color=\"008B90\"/></w:tcBorders>")
        }
    }

}

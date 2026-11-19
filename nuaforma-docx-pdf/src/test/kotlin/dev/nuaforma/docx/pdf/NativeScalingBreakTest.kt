package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeScalingBreakTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `malformed scale and unsupported break controls reject their original source without PDF output`() {
        val support = NativeWordFeatureSupport(temporary)
        for (bad in listOf("0", "601", "-80", "80.5", "invalid"))
            support.rejectChanged("/scaling-breaks/scaling/a4/source.docx", "word/document.xml", "w") {
                it.replaceFirst("w:val=\"50\"", "w:val=\"$bad\"")
            }
        for (bad in listOf("<w:w w:val=\"50\"/><w:w w:val=\"80\"/>", "<w:w val=\"50\"/>", "<w:w w:val=\"50\">text</w:w>"))
            support.rejectChanged("/scaling-breaks/scaling/a4/source.docx", "word/document.xml", "w") {
                it.replaceFirst("<w:w w:val=\"50\"/>", bad)
            }
        for (bad in listOf("w:type=\"column\"", "w:type=\"invalid\"", "type=\"page\"", "w:type=\"page\" w:clear=\"all\""))
            support.rejectChanged("/scaling-breaks/middle/a4/source.docx", "word/document.xml", "br") {
                it.replaceFirst("w:type=\"page\"", bad)
            }
        support.rejectChanged("/scaling-breaks/middle/a4/source.docx", "word/document.xml", "br") {
            it.replaceFirst("<w:pPr>", "<w:pPr><w:keepNext/>")
        }
        support.rejectChanged("/scaling-breaks/middle/a4/source.docx", "word/document.xml", "br") {
            it.replaceFirst("<w:body>", "<w:body><w:tbl><w:tblPr><w:tblW w:w=\"7200\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/><w:tblCellMar>" +
                listOf("top", "left", "bottom", "right").joinToString("") { edge -> "<w:$edge w:w=\"80\" w:type=\"dxa\"/>" } +
                "</w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w=\"7200\"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w=\"7200\" w:type=\"dxa\"/></w:tcPr>")
                .replaceFirst("<w:sectPr>", "</w:tc></w:tr></w:tbl><w:sectPr>")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `explicit breaks and scaled text retain Word page content`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val pattern = Regex("[HKCDWSMBERQP][0-9]{2}_(?:START|END|LEFT|BEGIN|INHERITED|RESET|BEFORE|AFTER|NEXT|FIRST|SECOND)")
        for (variant in listOf("scaling", "middle", "start", "end", "consecutive", "empty", "decorated")) {
            val resource = "/scaling-breaks/$variant/$paper/"
            val count = when (variant) { "scaling" -> 26; "middle" -> 3; "empty" -> 1; else -> 2 }
            val pages = when (variant) { "scaling" -> 1; "consecutive" -> 3; else -> 2 }
            val profiles = if (paper == "a4") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify(resource, profiles, pattern, count, pageCount = pages,
                horizontalTolerance = if (variant == "scaling") .35f else .21f)
        }
    }

}

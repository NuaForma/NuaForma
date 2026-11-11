package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeInactiveRunPropertiesTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `disabled double strike and normal scaling retain existing golden pixels including off alias`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (variant in listOf("zero", "false", "off")) support.verify("/inactive-run-properties/$variant/$paper/",
            if (variant == "off" && paper == "a4") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard"),
            Regex("(?!)"), 0, golden = "/preferred-table-widths/$paper/page-1.png")
    }

    @Test
    fun `active or malformed original properties keep source context and publish no partial PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        for (replacement in listOf("<w:dstrike/>", "<w:dstrike w:val=\"on\"/>", "<w:dstrike w:val=\"unknown\"/>",
            "<w:dstrike w:val=\"0\" extra=\"1\"/>", "<w:dstrike w:val=\"0\"/><w:dstrike w:val=\"0\"/>")) {
            support.rejectChanged("/inactive-run-properties/zero/a4/source.docx", "word/document.xml", "dstrike") {
                it.replaceFirst("<w:dstrike w:val=\"0\"/>", replacement)
            }
        }
        for (value in listOf("99", "105", "100.0", "-100")) {
            support.rejectChanged("/inactive-run-properties/zero/a4/source.docx", "word/document.xml", "/w:w[") {
                it.replaceFirst("<w:w w:val=\"100\"/>", "<w:w w:val=\"$value\"/>")
            }
        }
    }

}

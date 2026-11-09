package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativePreferredWidthSpellingTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `percent decimal and fractional spellings retain existing qualified table pixels`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (variant in listOf("percent", "decimal", "fraction")) support.verify("/preferred-width-spellings/$variant/$paper/",
            if (variant == "percent") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard"),
            Regex("(?!)"), 0, golden = "/preferred-table-widths/$paper/page-1.png")
    }

    @Test
    fun `invalid percent precision and units keep original width diagnostics`() {
        val support = NativeWordFeatureSupport(temporary)
        for (replacement in listOf("w:w=\"0.01%\" w:type=\"pct\"", "w:w=\"80%\" w:type=\"dxa\"",
            "w:w=\"100.01%\" w:type=\"pct\"")) {
            support.rejectChanged("/preferred-width-spellings/percent/a4/source.docx", "word/document.xml", "tblW") {
                it.replaceFirst(Regex("w:w=\"[0-9.]+%\" w:type=\"pct\""), replacement)
            }
        }
    }

}

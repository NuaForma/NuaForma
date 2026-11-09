package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeThemeCellFillTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `direct and inherited theme fills match Word colors despite stale caches`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (variant in listOf("direct", "style")) support.verify("/theme-cell-fills/$variant/$paper/",
            if (variant == "direct") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard"),
            Regex("C[0-9]{2}[TC]"), 12, colors = true)
    }

    @Test
    fun `invalid original cell theme fills keep source context and emit no partial PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        for (replacement in listOf("w:themeFillShade=\"GG\"", "w:themeFillShade=\"D9\" extra=\"1\"")) {
            support.rejectChanged("/theme-cell-fills/direct/a4/source.docx", "word/document.xml", "shd") {
                it.replaceFirst("w:themeFillShade=\"D9\"", replacement)
            }
        }
    }

}

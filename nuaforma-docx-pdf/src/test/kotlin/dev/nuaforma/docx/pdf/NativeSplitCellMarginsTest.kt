package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeSplitCellMarginsTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `oversized first data rows use the first page and retain Word headers and every cell line`(paper: String) {
        NativeWordFeatureSupport(temporary).verify("/split-cell-margins/$paper/",
            if (paper == "a4") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard"),
            Regex("[SH][0-9]{2}[AB]"), 144, pageCount = 2)
    }

}

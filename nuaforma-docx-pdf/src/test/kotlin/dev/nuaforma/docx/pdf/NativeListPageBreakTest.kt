package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeListPageBreakTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `explicit page breaks retain list numbering and continuation geometry from Word`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (variant in listOf("middle", "leading", "trailing", "consecutive", "empty-first", "empty-last")) {
            val resource = "/list-page-breaks/$variant/$paper/"
            val profiles = if (paper == "a4" && !variant.startsWith("empty-"))
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            val pages = when (variant) { "consecutive" -> 5; "trailing", "empty-first", "empty-last" -> 2; else -> 3 }
            support.verify(resource, profiles, Regex("L[01][01]_(?:START|TAIL)|END_LIST_PAGE_BREAK|[12][.]"),
                if (variant.startsWith("empty-")) 4 else 6, pageCount = pages)
        }
    }

}

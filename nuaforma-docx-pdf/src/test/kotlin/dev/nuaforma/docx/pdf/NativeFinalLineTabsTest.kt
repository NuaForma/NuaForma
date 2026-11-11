package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeFinalLineTabsTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `final justified lines retain Word tab anchors for every qualified stop and leader`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        support.verify("/final-line-tabs/$paper/",
            if (paper == "a4") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard"),
            Regex("T[0-9]{2}[ABC]|12[0-9]{2},45|67[0-9]{2},90"), 48, normalizeTabLeaders = true)
        support.verify("/final-line-tabs-left-control/$paper/", listOf("standard"),
            Regex("T[0-9]{2}[ABC]|12[0-9]{2},45|67[0-9]{2},90"), 48,
            golden = "/final-line-tabs/$paper/page-1.png", normalizeTabLeaders = true)
    }

}

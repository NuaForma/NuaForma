package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeWhitespacePaddingTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `malformed original whitespace and image extents publish no partial PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        for (bad in listOf("xml:space=\"invalid\"", "w:space=\"preserve\"", "space=\"preserve\""))
            support.rejectChanged("/whitespace-padding/preserve/a4/source.docx", "word/document.xml", "w:t") {
                it.replaceFirst("xml:space=\"preserve\"", bad)
            }
        for (bad in listOf("-1", "1.5", "182880001", "invalid"))
            support.rejectChanged("/whitespace-padding/effect-zero/a4/source.docx", "word/document.xml", "effectExtent") {
                it.replaceFirst(" r=\"0\"", " r=\"$bad\"")
            }
        for (change in listOf("missing", "duplicate", "foreign"))
            support.rejectChanged("/whitespace-padding/effect-zero/a4/source.docx", "word/document.xml", "effectExtent") {
                val element = Regex("<[^<>\\s]+:effectExtent\\b[^<>]*/>").find(it)!!.value
                val replacement = when (change) {
                    "missing" -> element.replace(" r=\"0\"", "")
                    "duplicate" -> element + element
                    else -> element.replace(Regex("^<[^:]+:"), "<w:")
                }
                it.replaceFirst(element, replacement)
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `Word whitespace policies and inline image padding retain complete page content`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val pattern = Regex("[AFWEITG][0-9]{2}_(?:START|END|BEGIN|BEFORE|AFTER|NEXT|PRIOR|CELL|BODY)")
        for (variant in listOf("missing", "default", "preserve", "trimmed", "effect-zero", "effect-small", "effect-right-bottom", "effect-left-top", "effect-all", "effect-table", "effect-cell-exact", "effect-bands")) {
            val resource = "/whitespace-padding/$variant/$paper/"
            val count = when { variant == "effect-bands" -> 1; variant == "effect-cell-exact" -> 4; variant.startsWith("effect-") -> 3; else -> 23 }
            val profiles = if (paper == "a4") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify(resource, profiles, pattern, count)
        }
    }

}

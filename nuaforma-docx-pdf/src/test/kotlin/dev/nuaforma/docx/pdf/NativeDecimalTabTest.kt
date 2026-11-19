package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeDecimalTabTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `first numeric fields retain actual Word content`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val tokens = mapOf("amount-comma" to "12,50", "amount-grouping" to "12.50", "currency" to "12,50",
            "grouped-comma" to "1.234,50", "mixed-separators" to "1,234.50", "unit-suffix" to "12X", "text-only" to "ABC",
            "marker-prefix" to "L01_12,50_END", "negative" to "-12,50", "parentheses" to "(12,50)", "space-separated" to "345,50",
            "two-numbers" to "AB12CD34", "time" to "12:50", "prefix-grouping" to "12,5", "fraction" to ",50", "text-comma" to "ABC,",
            "currency-fraction" to ",50", "prefix-comma" to "A,B", "trailing-grouping" to "12.", "repeated-grouping" to "12..34",
            "grouping-before-unit" to "12.", "separator-only" to ",")
        for ((variant, token) in tokens) {
            val folder = "/decimal-tabs/$variant/$paper/"
            val profiles = if (paper == "a4" && variant in setOf("currency", "fraction", "grouped-comma", "marker-prefix"))
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify(folder, profiles, Regex("L00_START|" + Regex.escape(token)), 2)
        }
    }

}

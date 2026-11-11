package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeImplicitTableIndentTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `omitted and explicit zero indents retain their distinct Word placement across modes`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (mode in listOf("absent", "12", "14", "15")) for (kind in listOf("implicit", "explicit")) {
            val profiles = if (mode == "absent" && kind == "implicit") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/implicit-table-indent/$mode-$kind/$paper/", profiles, Regex("I[0-9]{2}[AB]"), 6)
        }
    }

}

package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeListTabTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `tabs inside numbered paragraphs retain actual Word content`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (variant in listOf("default-left", "explicit-left", "right", "center", "decimal", "collision", "wrapped", "soft-lines")) {
            val resource = "/list-tabs/$variant/$paper/"
            val profiles = if (paper == "a4" && variant in setOf("default-left", "decimal", "wrapped", "soft-lines"))
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify(resource, profiles, Regex("L[01][0-3]_(?:START|TAIL|12,50_END|NEXT|FINAL)"),
                if (variant == "soft-lines") 8 else 4)
        }
    }

}

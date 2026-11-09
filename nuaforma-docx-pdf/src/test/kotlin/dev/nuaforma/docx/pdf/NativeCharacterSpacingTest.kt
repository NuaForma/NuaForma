package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeCharacterSpacingTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `tracking styles resets wrapping and decorations retain Word geometry in every profile`(paper: String) {
        NativeWordFeatureSupport(temporary).verify("/character-spacing/$paper/",
            listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b"), Regex("P[0-9]{2}|W[0-9]{3}"), 64, tails = true)
    }

    @Test
    fun `invalid original run spacing keeps source context and emits no partial PDF`() {
        val support = NativeWordFeatureSupport(temporary)
        for (replacement in listOf("<w:spacing/>", "<w:spacing w:val=\"1.5\"/>",
            "<w:spacing w:val=\"288001\"/>", "<w:spacing w:val=\"1\" extra=\"1\"/>",
            "<w:spacing w:val=\"1\"/><w:spacing w:val=\"2\"/>")) {
            support.rejectChanged("/character-spacing/a4/source.docx", "word/document.xml", "spacing") {
                it.replaceFirst("<w:spacing w:val=\"0\"/>", replacement)
            }
        }
    }

}

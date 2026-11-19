package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeFloatingDrawingTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `invalid original drawing policies retain source context and publish nothing`() {
        val support = NativeWordFeatureSupport(temporary)
        for ((before, after) in listOf("relativeHeight=\"0\"" to "relativeHeight=\"4294967296\"",
            "relativeHeight=\"0\"" to "relativeHeight=\"-1\"", "locked=\"0\"" to "locked=\"invalid\"",
            "relativeHeight=\"0\"" to "relativeHeight=\"1.5\"", "locked=\"0\"" to "w:locked=\"0\"",
            "allowOverlap=\"1\"" to "allowOverlap=\"0\"", "simplePos=\"0\"" to "simplePos=\"1\"",
            "distL=\"0\"" to "distL=\"1.5\"", "distL=\"0\"" to "distL=\"182880001\""))
            support.rejectChanged("/floating-drawings/control/a4/source.docx", "word/document.xml", "anchor") { it.replaceFirst(before, after) }
        support.rejectChanged("/floating-drawings/metadata-ids/a4/source.docx", "word/document.xml", "anchor") {
            it.replaceFirst("0A1B2C3D", "not-hex")
        }
        support.rejectChanged("/floating-drawings/metadata-ids/a4/source.docx", "word/document.xml", "anchor") {
            it.replaceFirst("wp14:anchorId", "w:anchorId")
        }
        support.rejectChanged("/floating-drawings/control/a4/source.docx", "word/document.xml", "docPr") {
            it.replaceFirst("id=\"1\"", "id=\"-1\"")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `floating drawing layers retain Word page content`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (variant in listOf("control", "z-forward", "z-reverse", "z-red-front", "z-equal-forward", "z-equal-reverse",
            "tie-ids-swapped", "tie-ids-renumbered", "tie-maximum", "body-behind", "body-above", "metadata-ids", "locked",
            "layout-outside-cell", "distance-unwrapped", "image-body-behind", "image-body-above", "image-over-frame",
            "frame-over-image", "image-tie-after", "frame-tie-after")) {
            val resource = "/floating-drawings/$variant/$paper/"
            val profiles = if (paper == "a4") listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify(resource, profiles, Regex("F01_NEXT"), 1)
        }
    }

}

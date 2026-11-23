package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeFloatingMetadataTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `active or malformed original metadata cannot be repaired by typed binding`() {
        val support = NativeWordFeatureSupport(temporary)
        for (kind in listOf("frame", "image", "line")) {
            val root = "/floating-metadata/$kind-"
            for (bad in listOf("true", "1", "invalid"))
                support.rejectChanged(root + "hidden-zero/a4/source.docx", "word/document.xml", "anchor") {
                    it.replaceFirst("hidden=\"0\"", "hidden=\"$bad\"")
                }
            support.rejectChanged(root + "hidden-zero/a4/source.docx", "word/document.xml", "anchor") {
                it.replaceFirst("hidden=\"0\"", "xmlns:alien=\"urn:nuaforma-test-unsupported\" alien:hidden=\"0\"")
            }
            for (bad in listOf("1.5", "182880001", "-182880001", "invalid"))
                support.rejectChanged(root + "inactive-simple-position/a4/source.docx", "word/document.xml", "simplePos") {
                    it.replaceFirst("x=\"914400\"", "x=\"$bad\"")
                }
            support.rejectChanged(root + "inactive-simple-position/a4/source.docx", "word/document.xml", "simplePos") {
                it.replaceFirst(" y=\"10106025\"", "")
            }
            support.rejectChanged(root + "inactive-simple-position/a4/source.docx", "word/document.xml", "simplePos") {
                val element = Regex("<[^<>\\s]+:simplePos\\b[^<>]*/>").find(it)!!.value
                it.replaceFirst(element, element + element)
            }
            support.rejectChanged(root + "relative-size-zero/a4/source.docx", "word/document.xml", "sizeRelH") {
                val element = Regex("<([^<>\\s]+:pctWidth)>0</\\1>").find(it)!!.value
                it.replaceFirst(element, element.replace(">0<", ">100001<"))
            }
            support.rejectChanged(root + "relative-size-zero/a4/source.docx", "word/document.xml", "sizeRelH") {
                val element = Regex("<([^<>\\s]+:sizeRelH)\\b[^>]*>.*?</\\1>").find(it)!!.value
                it.replaceFirst(element, element + element)
            }
            support.rejectChanged(root + "relative-size-zero/a4/source.docx", "word/document.xml", "sizeRelH") {
                it.replaceFirst("relativeFrom=\"margin\"", "relativeFrom=\"invalid\"")
            }
            support.rejectChanged(root + "relative-size-zero/a4/source.docx", "word/document.xml", "sizeRelH") {
                val match = Regex("<([^<>\\s]+):sizeRelH\\b[^>]*>.*?</\\1:sizeRelH>").find(it)!!
                val prefix = match.groupValues[1]
                val foreign = match.value.replace("$prefix:sizeRelH", "alien:sizeRelH")
                    .replace("<alien:sizeRelH", "<alien:sizeRelH xmlns:alien=\"urn:nuaforma-test-unsupported\"")
                it.replaceFirst(match.value, foreign)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `inactive anchor values retain actual Word page content`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (kind in listOf("frame", "image", "line")) for (mode in listOf("control", "hidden-zero", "hidden-false", "relative-size-zero", "inactive-simple-position", "combined")) {
            val variant = "$kind-$mode"
            val resource = "/floating-metadata/$variant/$paper/"
            val profiles = if (paper == "a4" && mode == "combined")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify(resource, profiles, Regex("F01_NEXT"), 1)
        }
    }

}

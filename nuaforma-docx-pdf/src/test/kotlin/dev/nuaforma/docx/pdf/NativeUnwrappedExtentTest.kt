package dev.nuaforma.docx.pdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class NativeUnwrappedExtentTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `active effects wrapping extents and frame text policies retain their exact source`() {
        val support = NativeWordFeatureSupport(temporary)
        for ((before, after, construct) in listOf(
            Triple("anchorCtr=\"0\"", "anchorCtr=\"1\"", "bodyPr"),
            Triple("upright=\"1\"", "upright=\"invalid\"", "bodyPr"),
            Triple("rot=\"0\"", "rot=\"5400000\"", "bodyPr"),
            Triple("rot=\"0\"", "rot=\"invalid\"", "xfrm"),
            Triple("lim=\"800000\"", "lim=\"100000\"", "miter"),
            Triple("type=\"none\"", "type=\"triangle\"", "headEnd")))
            support.rejectChanged("/unwrapped-extents/frame-office/a4/source.docx", "word/document.xml", construct) {
                it.replaceFirst(before, after)
            }
        support.rejectChanged("/unwrapped-extents/image-small/a4/source.docx", "word/document.xml", "effectExtent") {
            val match = Regex("<([^<>\\s]+):wrapNone\\s*/>").find(it)!!
            it.replaceFirst(match.value, "<${match.groupValues[1]}:wrapTopAndBottom/>")
        }
        for (kind in listOf("frame", "image", "line")) {
            val path = "/unwrapped-extents/$kind-small/a4/source.docx"
            support.rejectChanged(path, "word/document.xml", "effectExtent") {
                it.replaceFirst(" r=\"9525\"", " r=\"-1\"")
            }
            support.rejectChanged(path, "word/document.xml", "effectLst") {
                val closing = Regex("</[^<>\\s]+:spPr>").find(it)!!.value
                val shadow = """<fx:effectLst xmlns:fx="http://schemas.openxmlformats.org/drawingml/2006/main"><fx:outerShdw blurRad="63500" dist="25400" dir="5400000" algn="ctr" rotWithShape="0"><fx:srgbClr val="000000"><fx:alpha val="50000"/></fx:srgbClr></fx:outerShdw></fx:effectLst>"""
                it.replaceFirst(closing, shadow + closing)
            }
        }
        support.rejectChanged("/unwrapped-extents/frame-control/a4/source.docx", "word/document.xml", "prstDash") {
            val closing = Regex("</[^<>\\s]+:ln>").find(it)!!.value
            it.replaceFirst(closing, """<fx:prstDash xmlns:fx="http://schemas.openxmlformats.org/drawingml/2006/main" val="dash"/>""" + closing)
        }
        support.rejectChanged("/unwrapped-extents/frame-control/a4/source.docx", "word/document.xml", "alpha") {
            val closing = Regex("</[^<>\\s]+:srgbClr>").find(it)
            if (closing != null) it.replaceFirst(closing.value, """<fx:alpha xmlns:fx="http://schemas.openxmlformats.org/drawingml/2006/main" val="50000"/>""" + closing.value)
            else {
                val color = Regex("<([^<>\\s]+:srgbClr)\\b[^<>]*/>").find(it)!!
                val rewritten = color.value.replace(Regex("\\s*/>$"), ">") + """<fx:alpha xmlns:fx="http://schemas.openxmlformats.org/drawingml/2006/main" val="50000"/>""" + "</${color.groupValues[1]}>"
                it.replaceFirst(color.value, rewritten)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `non wrapping extents and unrotated frame flags retain actual Word content`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (kind in listOf("frame", "image", "line")) for (mode in listOf("control", "small", "large", "office")) {
            val variant = "$kind-$mode"
            val resource = "/unwrapped-extents/$variant/$paper/"
            val profiles = if (paper == "a4" && mode == "office")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify(resource, profiles, Regex("F01_NEXT"), 1)
        }
    }

}

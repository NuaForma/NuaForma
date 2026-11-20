package dev.nuaforma.docx.pdf

import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NativeDecorativePictureTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `an anchored decorative picture keeps its paint and drawing order`() {
        val support = NativeWordFeatureSupport(temporary)
        val original = support.bytes(source)
        val parts = PdfFixture.unzip(original).toMutableMap()
        val xml = parts.getValue(part).decodeToString()
        check(picture in xml && graphic in xml)
        parts[part] = xml.replace(picture, property(picture, "ns1:docPr", flag))
            .replace(graphic, property(graphic, "ns3:cNvPr", flag)).toByteArray()

        fun render(docx: ByteArray) = Loader.loadPDF(support.generate(docx, "standard")).use { pdf ->
            PDFRenderer(pdf).renderImageWithDPI(0, 96f)
        }

        assertImagesMatch(render(original), render(PdfFixture.zip(parts))) {
            pixelTolerance = 0.0
            allowedDifferentPixelsPercent = 0.0
            threshold = 1
        }
    }

    @Test
    fun `malformed and unknown picture extensions retain their source paths`() {
        val support = NativeWordFeatureSupport(temporary)
        for ((element, extension) in listOf(
            "decorative" to flag.replace("val=\"1\"", "val=\"invalid\""),
            "decorative" to flag.replace("val=\"1\"", "w:val=\"1\""),
            "ext" to "$flag$flag",
        )) support.rejectChanged(source, part, element) { it.replace(picture, property(picture, "ns1:docPr", extension)) }
        support.rejectChanged(source, part, "decorative") {
            it.replace(graphic, property(graphic, "ns3:cNvPr", flag.replace("val=\"1\"", "val=\"invalid\"")))
        }
        support.rejectChanged(source, part, "extLst") {
            it.replace(picture, property(picture, "ns1:docPr", flag).replace(uri, "{00000000-0000-0000-0000-000000000000}"))
        }
    }

    private fun property(source: String, name: String, extension: String) = source.removeSuffix(" />") +
        "><a:extLst><a:ext uri=\"$uri\">$extension</a:ext></a:extLst></$name>"

    private companion object {
        const val source = "/floating-drawings/image-over-frame/a4/source.docx"
        const val part = "word/document.xml"
        const val picture = """<ns1:docPr id="2" name="picture2" descr="NuaForma" />"""
        const val graphic = """<ns3:cNvPr id="1" name="NuaForma logo" />"""
        const val uri = "{C183D7F6-B498-43B3-948B-1728B52AA6E4}"
        const val flag = """<adec:decorative xmlns:adec="http://schemas.microsoft.com/office/drawing/2017/decorative" val="1"/>"""
    }
}

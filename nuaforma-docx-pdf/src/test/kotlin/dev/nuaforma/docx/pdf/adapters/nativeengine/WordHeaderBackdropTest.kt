package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordHeaderBackdropTest {
    private val xml = ConversionXml(GenerationContext())
    private val unlabelled = """
        <wps:wsp xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
          xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:x="urn:foreign">
          <wps:cNvSpPr/>
          <wps:style>
            <a:lnRef idx="0"><a:schemeClr val="accent1"><a:shade val="100000"/></a:schemeClr></a:lnRef>
            <a:fillRef idx="3"><a:schemeClr val="lt2"/></a:fillRef>
            <a:effectRef idx="0"><a:schemeClr val="dk2"/></a:effectRef>
            <a:fontRef idx="minor"><a:schemeClr val="accent6"/></a:fontRef>
          </wps:style>
          <wps:bodyPr rot="0" fromWordArt="false" wrap="square">
            <a:prstTxWarp prst="textNoShape"><a:avLst/></a:prstTxWarp><a:noAutofit/>
          </wps:bodyPr>
        </wps:wsp>
        """.trimIndent()

    private fun qualifies(source: String) = WordHeaderBackdrop.isUnlabelledShape(xml.read(source.toByteArray()))

    @Test
    fun `plain header backdrop references accept bounded colors and inactive formatting`() {
        assertTrue(qualifies(unlabelled))
        assertTrue(qualifies(unlabelled.replace("idx=\"minor\"", "idx=\"none\"").replace("val=\"100000\"", "val=\"0\"")
            .replace("fromWordArt=\"false\"", "fromWordArt=\"0\"")))
    }

    @Test
    fun `visible labels unknown shapes and active formatting cannot masquerade as unlabelled geometry`() {
        val invalid = listOf(
            unlabelled.replace("<wps:cNvSpPr/>", ""), unlabelled.replace("<wps:cNvSpPr/>", "<wps:cNvSpPr txBox=\"1\"/>"),
            unlabelled.replace("<wps:cNvSpPr/>", "<wps:cNvSpPr>label</wps:cNvSpPr>"),
            unlabelled.replace("<wps:cNvSpPr/>", "<wps:cNvSpPr><a:extLst/></wps:cNvSpPr>"),
            unlabelled.replace("wps:style", "x:style"), unlabelled.replace("<wps:style>", "<wps:style attr=\"1\">"),
            unlabelled.replace("<wps:style>", "<wps:style>text"), unlabelled.replace("a:fillRef", "a:other"),
            unlabelled.replace("a:lnRef", "x:lnRef"), unlabelled.replace("<a:lnRef ", "<a:lnRef attr=\"1\" "),
            unlabelled.replace("<a:lnRef idx=\"0\">", "<a:lnRef idx=\"0\">text"),
            unlabelled.replace("<a:lnRef idx=\"0\">", "<a:lnRef idx=\"-1\">"),
            unlabelled.replace("<a:lnRef idx=\"0\">", "<a:lnRef idx=\"unknown\">"),
            unlabelled.replace("<a:effectRef idx=\"0\">", "<a:effectRef idx=\"1\">"),
            unlabelled.replace("idx=\"minor\"", "idx=\"unsupported\""),
            unlabelled.replace("<a:schemeClr val=\"accent6\"/>", ""),
            unlabelled.replace("<a:schemeClr val=\"accent6\"/>", "<x:schemeClr val=\"accent6\"/>"),
            unlabelled.replace("<a:schemeClr val=\"accent6\"/>", "<a:srgbClr val=\"FF0000\"/>"),
            unlabelled.replace("<a:schemeClr val=\"accent6\"/>", "<a:schemeClr val=\"accent6\" other=\"yes\"/>"),
            unlabelled.replace("val=\"accent6\"", "val=\"dk3\""),
            unlabelled.replace("<a:schemeClr val=\"accent6\"/>", "<a:schemeClr val=\"accent6\">text</a:schemeClr>"),
            unlabelled.replace("a:shade", "x:shade"), unlabelled.replace("a:shade", "a:tint"),
            unlabelled.replace("<a:shade val=\"100000\"/>", "<a:shade val=\"100000\"/><a:shade val=\"0\"/>"),
            unlabelled.replace("<a:shade val=\"100000\"/>", "<a:shade val=\"100000\" extra=\"0\"/>"),
            unlabelled.replace("val=\"100000\"", "val=\"100001\""),
            unlabelled.replace("<a:shade val=\"100000\"/>", "<a:shade val=\"0\"><a:tint/></a:shade>"),
            unlabelled.replace("<a:shade val=\"100000\"/>", "<a:shade val=\"0\">text</a:shade>"),
            unlabelled.replace("wps:bodyPr", "x:bodyPr"), unlabelled.replace("rot=\"0\"", "rot=\"1\""),
            unlabelled.replace("wrap=\"square\"", "x:wrap=\"square\""), unlabelled.replace("wrap=\"square\"", "other=\"square\""),
            unlabelled.replace("fromWordArt=\"false\"", "fromWordArt=\"true\""),
            unlabelled.replace("<a:prstTxWarp", "text<a:prstTxWarp"),
            unlabelled.replace("<a:prstTxWarp prst=\"textNoShape\"><a:avLst/></a:prstTxWarp>", ""),
            unlabelled.replace("<a:avLst/>", ""), unlabelled.replace("a:prstTxWarp", "x:prstTxWarp"),
            unlabelled.replace("textNoShape", "textWave1"), unlabelled.replace("<a:avLst/>", "text<a:avLst/>"),
            unlabelled.replace("<a:avLst/>", "<a:avLst extra=\"1\"/>"),
            unlabelled.replace("<a:avLst/>", "<x:avLst/>"), unlabelled.replace("<a:avLst/>", "<a:custGeom/>"),
            unlabelled.replace("<a:noAutofit/>", "<a:spAutoFit/>"), unlabelled.replace("<a:noAutofit/>", "<x:noAutofit/>"),
            unlabelled.replace("<a:noAutofit/>", "<a:noAutofit/> <a:noAutofit/>"),
        )
        for ((index, source) in invalid.withIndex()) assertFalse(qualifies(source), "Backdrop must reject case $index")
    }
}

package dev.nuaforma.docx

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node

/** Independent OOXML author, deliberately not using the library under test. Synthetic data only. */
internal object SyntheticDocx {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val RELS = "http://schemas.openxmlformats.org/package/2006/relationships"

    fun parts(label: String = "Synthetic reference"): MutableMap<String, ByteArray> {
        val xml = linkedMapOf(
            "_rels/.rels" to relationships("officeDocument" to "word/document.xml", "metadata/core-properties" to "docProps/core.xml"),
            "docProps/core.xml" to """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>DOCX round trip reference</dc:title><dc:creator>NuaForma synthetic tests</dc:creator></cp:coreProperties>""",
            "word/_rels/document.xml.rels" to relationships(
                "styles" to "styles.xml", "numbering" to "numbering.xml", "header" to "header1.xml", "footer" to "footer1.xml",
                "image" to "media/mark.png", "settings" to "settings.xml", "fontTable" to "fontTable.xml",
            ).replace("</Relationships>", """<Relationship Id="link" Type="$R/hyperlink" Target="https://example.invalid/nuaforma" TargetMode="External"/></Relationships>"""),
            "word/styles.xml" to """<w:styles xmlns:w="$W"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial"/><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:color w:val="000000"/><w:sz w:val="36"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Reference"><w:name w:val="Reference"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="180"/></w:pPr><w:rPr><w:color w:val="007F87"/></w:rPr></w:style></w:styles>""",
            "word/numbering.xml" to """<w:numbering xmlns:w="$W"><w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>""",
            "word/header1.xml" to """<w:hdr xmlns:w="$W"><w:p><w:r><w:rPr><w:color w:val="007F87"/></w:rPr><w:t>NuaForma header {customer.name}</w:t></w:r></w:p></w:hdr>""",
            "word/footer1.xml" to """<w:ftr xmlns:w="$W"><w:p><w:r><w:t xml:space="preserve">Reference {reference}   Page </w:t></w:r><w:fldSimple w:instr="PAGE"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p></w:ftr>""",
            "word/settings.xml" to """<w:settings xmlns:w="$W"><w:zoom w:percent="100"/><w:defaultTabStop w:val="720"/></w:settings>""",
            "word/fontTable.xml" to """<w:fonts xmlns:w="$W"><w:font w:name="Arial"><w:family w:val="swiss"/></w:font></w:fonts>""",
            "word/document.xml" to """<w:document xmlns:w="$W" xmlns:r="$R" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>
                <w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>DOCX round trip reference</w:t></w:r></w:p>
                <w:p><w:r><w:t>This reference checks preservation of layout and document content.</w:t></w:r></w:p>
                <w:p><w:pPr><w:pStyle w:val="Reference"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>$label</w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t xml:space="preserve"> with italic detail and {customer.</w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t>name}</w:t></w:r></w:p>
                <w:p><w:bookmarkStart w:id="1" w:name="reference"/><w:r><w:t>Bookmarked reference {reference}</w:t></w:r><w:bookmarkEnd w:id="1"/></w:p>
                <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Numbering is retained</w:t></w:r></w:p>
                <w:p><w:hyperlink r:id="link"><w:r><w:rPr><w:u w:val="single"/></w:rPr><w:t>Passive external hyperlink</w:t></w:r></w:hyperlink></w:p>
                <w:tbl><w:tblPr><w:tblW w:w="9360" w:type="dxa"/><w:tblBorders><w:top w:val="single" w:sz="4" w:color="D9D9D9"/><w:left w:val="single" w:sz="4" w:color="D9D9D9"/><w:bottom w:val="single" w:sz="4" w:color="D9D9D9"/><w:right w:val="single" w:sz="4" w:color="D9D9D9"/><w:insideH w:val="single" w:sz="4" w:color="D9D9D9"/><w:insideV w:val="single" w:sz="4" w:color="D9D9D9"/></w:tblBorders><w:tblCellMar><w:top w:w="100" w:type="dxa"/><w:left w:w="120" w:type="dxa"/><w:bottom w:w="100" w:type="dxa"/><w:right w:w="120" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="4680"/><w:gridCol w:w="4680"/></w:tblGrid>
                ${row("Field", "Value", true)}${row("Reference", "{reference}", false)}${row("Customer", "{customer.name}", false)}</w:tbl>
                <w:p><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="914400" cy="457200"/><wp:docPr id="2" name="Synthetic mark" descr="Teal rectangle"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="0" name="mark.png"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="r5"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="914400" cy="457200"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>
                <w:p><w:r><w:pict><v:shape id="TextBox1" style="width:260pt;height:35pt" type="#_x0000_t202"><v:textbox inset="6pt,3pt,6pt,3pt"><w:txbxContent><w:p><w:r><w:t>Text box {customer.</w:t></w:r><w:r><w:t>name}</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>
                <w:p><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="4572000" cy="457200"/><wp:docPr id="3" name="Modern text box"/><a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"><wps:wsp xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"><wps:cNvSpPr txBox="1"/><wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="4572000" cy="457200"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:solidFill><a:srgbClr val="D9D9D9"/></a:solidFill></a:ln></wps:spPr><wps:txbx><w:txbxContent><w:p><w:r><w:t>Modern text box {customer.</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>name}</w:t></w:r></w:p></w:txbxContent></wps:txbx><wps:bodyPr rot="0" vert="horz" wrap="square" lIns="91440" tIns="45720" rIns="91440" bIns="45720" anchor="ctr"><a:noAutofit/></wps:bodyPr></wps:wsp></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>
                <w:p><w:r><w:pict><v:shape id="MarginBox" style="position:absolute;left:0;top:0;width:120pt;height:28pt;rotation:90;margin-left:-16pt;margin-top:-65pt;mso-position-horizontal:right;mso-position-horizontal-relative:page;mso-position-vertical:bottom;mso-position-vertical-relative:margin" type="#_x0000_t202"><v:textbox style="mso-layout-flow-alt:bottom-to-top" inset="0,0,0,0"><w:txbxContent><w:p><w:pPr><w:textDirection w:val="btLr"/></w:pPr><w:r><w:rPr><w:sz w:val="18"/></w:rPr><w:t>{reference}</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>
                <w:p><w:pPr>${section(false, true)}</w:pPr></w:p>
                <w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>Landscape section</w:t></w:r></w:p>
                <w:p><w:r><w:t>This section has its own page geometry and retains the same header and footer.</w:t></w:r></w:p>
                ${section(true, false)}</w:body></w:document>""".trimIndent(),
        )
        val overrides = xml.keys.filter { it != "[Content_Types].xml" && !it.endsWith(".rels") }.joinToString("") { name ->
            val type = when (name) {
                "word/document.xml" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                "docProps/core.xml" -> "application/vnd.openxmlformats-package.core-properties+xml"
                else -> "application/vnd.openxmlformats-officedocument.wordprocessingml.${name.substringAfterLast('/').substringBefore('.').replace("header1", "header").replace("footer1", "footer")}+xml"
            }
            """<Override PartName="/$name" ContentType="$type"/>"""
        }
        xml["[Content_Types].xml"] = """<Types xmlns="$TYPES"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="png" ContentType="image/png"/>$overrides</Types>"""
        val parts = xml.mapValuesTo(linkedMapOf()) { it.value.toByteArray() }
        val image = BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { it.color = Color(0, 127, 135); it.fillRect(0, 0, 120, 60); it.dispose() }
        parts["word/media/mark.png"] = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return parts
    }

    private fun row(left: String, right: String, header: Boolean) = """<w:tr>${if (header) "<w:trPr><w:tblHeader/></w:trPr>" else ""}${listOf(left, right).joinToString("") { """<w:tc><w:tcPr><w:tcW w:w="4680" w:type="dxa"/>${if (header) "<w:shd w:fill=\"E7F1F2\"/>" else ""}<w:vAlign w:val="center"/></w:tcPr><w:p><w:r>${if (header) "<w:rPr><w:b/></w:rPr>" else ""}<w:t>$it</w:t></w:r></w:p></w:tc>""" }}</w:tr>"""
    private fun section(landscape: Boolean, breakPage: Boolean) = """<w:sectPr><w:headerReference w:type="default" r:id="r3"/><w:footerReference w:type="default" r:id="r4"/>${if (breakPage) "<w:type w:val=\"nextPage\"/>" else ""}<w:pgSz w:w="${if (landscape) 15840 else 12240}" w:h="${if (landscape) 12240 else 15840}"${if (landscape) " w:orient=\"landscape\"" else ""}/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/></w:sectPr>"""

    private fun relationships(vararg pairs: Pair<String, String>) = """<Relationships xmlns="$RELS">${pairs.mapIndexed { index, pair -> """<Relationship Id="r${index + 1}" Type="${if (pair.first == "metadata/core-properties") "http://schemas.openxmlformats.org/package/2006/relationships/" else "$R/"}${pair.first}" Target="${pair.second}"/>""" }.joinToString("")}</Relationships>"""

    fun zip(parts: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> parts.forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name).apply { time = 0 })
            zip.write(value)
            zip.closeEntry()
        } }
    }.toByteArray()

    fun unzip(bytes: ByteArray): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().also { parts ->
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) { if (!entry.isDirectory) parts[entry.name] = zip.readBytes(); entry = zip.nextEntry }
        }
    }

    /** Namespace-aware structure; formatting whitespace and namespace declarations are not content. */
    fun semantic(bytes: ByteArray): String {
        val factory = DocumentBuilderFactory.newDefaultInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        fun describe(node: Node): String = when (node.nodeType) {
            Node.ELEMENT_NODE -> "{${node.namespaceURI}}${node.localName}" + (0 until node.attributes.length)
                .map { node.attributes.item(it) }.filter { it.namespaceURI != XMLConstants.XMLNS_ATTRIBUTE_NS_URI &&
                    !(it.namespaceURI == "http://schemas.openxmlformats.org/markup-compatibility/2006" && it.localName == "Ignorable" && it.nodeValue.isEmpty()) }
                .map {
                    // These declared XML Schema booleans have equivalent 1/true and 0/false forms.
                    val boolean = (node.namespaceURI == W && node.localName == "style" && it.namespaceURI == W && it.localName == "default") ||
                        (node.namespaceURI == "http://schemas.microsoft.com/office/word/2010/wordprocessingShape" && node.localName == "cNvSpPr" &&
                            it.namespaceURI == null && it.localName == "txBox")
                    val value = if (boolean)
                        when (it.nodeValue) { "1", "true" -> "true"; "0", "false" -> "false"; else -> it.nodeValue }
                    else it.nodeValue
                    "{${it.namespaceURI}}${it.localName ?: it.nodeName}=$value"
                }.sorted().joinToString("|", "[", "]") +
                (0 until node.childNodes.length).map { describe(node.childNodes.item(it)) }.let { children ->
                    // OPC declarations, relationships and property bags are unordered; Word content is not.
                    (if (node.localName in setOf("Types", "Relationships", "coreProperties")) children.sorted() else children).joinToString("", "(", ")")
                }
            Node.TEXT_NODE -> if (node.nodeValue.isBlank()) "" else node.nodeValue
            else -> ""
        }
        return describe(factory.newDocumentBuilder().parse(bytes.inputStream()).documentElement)
    }
}

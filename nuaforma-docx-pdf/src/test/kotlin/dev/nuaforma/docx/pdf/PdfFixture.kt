package dev.nuaforma.docx.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object PdfFixture {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val V = "urn:schemas-microsoft-com:vml"
    fun parts(): MutableMap<String, ByteArray> = linkedMapOf(
        "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""".toByteArray(),
        "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""".toByteArray(),
        "word/_rels/document.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="styles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""".toByteArray(),
        "word/styles.xml" to """<w:styles xmlns:w="$W"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="DejaVu Sans" w:hAnsi="DejaVu Sans"/><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style></w:styles>""".toByteArray(),
        "word/document.xml" to """<w:document xmlns:w="$W" xmlns:v="$V"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hello {name}</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>""".toByteArray(),
    )
    fun zip(parts: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { stream ->
        ZipOutputStream(stream).use { output -> parts.forEach { (name, bytes) -> output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry() } }
    }.toByteArray()
    fun unzip(bytes: ByteArray): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().also { parts ->
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) { parts[entry.name] = zip.readBytes(); entry = zip.nextEntry }
        }
    }
    fun inline() = """<w:p><w:r><w:pict xmlns:z="urn:synthetic-preserved"><v:shape id="box" style="width:260pt;height:35pt" type="#_x0000_t202"><v:textbox inset="6pt,3pt,6pt,3pt"><w:txbxContent><w:p><w:r><w:rPr><w:i/></w:rPr><w:t z:attribute="retained">Native text</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>"""
    fun margin() = """<w:p><w:r><w:pict><v:shape id="margin" style="position:absolute;width:180pt;height:42pt;rotation:90;left:0;top:0;margin-left:-16pt;margin-top:-65pt;mso-position-horizontal:right;mso-position-horizontal-relative:page;mso-position-vertical:bottom;mso-position-vertical-relative:margin" type="#_x0000_t202"><v:textbox style="mso-layout-flow-alt:bottom-to-top" inset="0,0,0,0"><w:txbxContent><w:p><w:r><w:rPr><w:rFonts w:ascii="Barcode"/><w:sz w:val="60"/></w:rPr><w:t>*NF001*</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>"""
}

package dev.nuaforma.pdf.adapters.pdfbox

import dev.nuaforma.pdf.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.io.ByteArrayInputStream

internal object ArchivalMetadata {
    fun apply(pdf: PDDocument, options: PdfOutputOptions) {
        options.title?.let { pdf.documentInformation.title = it }
        options.author?.let { pdf.documentInformation.author = it }
        val part = options.profile.archivalPart ?: return
        pdf.documentInformation.producer = "NuaForma"
        pdf.documentInformation.creator = "NuaForma"
        fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        val title = options.title?.let { "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">${xml(it)}</rdf:li></rdf:Alt></dc:title>" } ?: ""
        val author = options.author?.let { "<dc:creator><rdf:Seq><rdf:li>${xml(it)}</rdf:li></rdf:Seq></dc:creator>" } ?: ""
        val xmp = """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/" xmlns:pdf="http://ns.adobe.com/pdf/1.3/" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
            <pdfaid:part>$part</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance><pdf:Producer>NuaForma</pdf:Producer><xmp:CreatorTool>NuaForma</xmp:CreatorTool><dc:format>application/pdf</dc:format>$title$author
            </rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end="w"?>""".trimIndent()
        pdf.documentCatalog.metadata = PDMetadata(pdf).apply { importXMPMetadata(xmp.toByteArray(Charsets.UTF_8)) }
        val intent = PDOutputIntent(pdf, ByteArrayInputStream(ICC_Profile.getInstance(ColorSpace.CS_sRGB).data))
        intent.info = "sRGB IEC61966-2.1"
        intent.outputConditionIdentifier = "sRGB IEC61966-2.1"
        intent.registryName = "http://www.color.org"
        pdf.documentCatalog.addOutputIntent(intent)
    }
}

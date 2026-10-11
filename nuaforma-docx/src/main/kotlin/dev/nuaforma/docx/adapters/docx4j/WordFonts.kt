package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.BoundedStreams
import dev.nuaforma.docx.internal.DocxFailure
import dev.nuaforma.docx.internal.FontPolicy
import dev.nuaforma.docx.internal.OpenTypeMetadata
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.openpackaging.parts.AbstractFontPart
import org.docx4j.openpackaging.parts.JaxbXmlPart
import org.docx4j.openpackaging.parts.PartName
import org.docx4j.openpackaging.parts.WordprocessingML.FontTablePart
import org.docx4j.openpackaging.parts.WordprocessingML.ObfuscatedFontPart
import org.docx4j.wml.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import jakarta.xml.bind.JAXBException

/** No font mapper, extraction cache, host lookup or global font registration. */
internal object WordFonts {
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"

    fun apply(document: WordprocessingMLPackage, request: GenerationRequest, context: GenerationContext) {
        val policy = FontPolicy.read(request.requirements.output.options)
        if (request.resources.keys != policy.fonts.map { it.resource }.toSet()) fail("Supply exactly the declared font resources; unused or missing resources are not accepted")
        if (policy.mode == "preserve") return
        val declared = declaredFonts(document, context)
        if (!policy.fonts.filter { it.face == "regular" }.map { it.family }.toSet().containsAll(declared)) fail("A declared template font family has no supplied regular face")
        var total = 0L
        val inspected = mutableListOf<Pair<FontPolicy.FontResource, ByteArray>>()
        policy.fonts.forEach { font ->
            context.checkActive()
            val source = request.resources.getValue(font.resource)
            val buffer = ByteArrayOutputStream()
            source.openStream().use { BoundedStreams.copy(it, buffer, minOf(16L * 1024 * 1024, minOf(64L * 1024 * 1024, context.limits.maxExpandedBytes) - total), context) }
            val bytes = buffer.toByteArray()
            total += bytes.size
            val metadata = OpenTypeMetadata.read(bytes)
            if (font.family !in metadata.families || font.face != metadata.face) fail("Supplied font family or face does not match its declaration")
            if (policy.mode == "embed") metadata.requireEditableEmbedding()
            if (policy.mode == "embed") inspected.add(font to bytes)
        }
        if (policy.mode == "embed") inspected.forEach { (font, bytes) ->
            context.checkActive()
            embed(document, font, bytes)
        }
    }

    private fun declaredFonts(document: WordprocessingMLPackage, context: GenerationContext): Set<String> {
        val names = mutableSetOf<String>()
        var defaultAscii = false
        var defaultAnsi = false
        var total = 0L
        document.parts.parts.values.filterIsInstance<JaxbXmlPart<*>>().forEach { part ->
            context.checkActive()
            if (part.getPartName().name.contains("/theme/")) return@forEach
            val buffer = ByteArrayOutputStream()
            try { part.marshal(BoundedStreams.Output(buffer, context.limits.maxExpandedBytes - total, context)) }
            catch (error: JAXBException) {
                generateSequence<Throwable>(error) { it.cause }.filterIsInstance<GenerationException>().firstOrNull()?.let { throw it }
                DocxFailure.fail(DiagnosticCodes.GENERATION_FAILED, "DOCX fonts cannot be inspected safely")
            }
            total += buffer.size()
            val factory = XMLInputFactory.newDefaultFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            }
            val reader = factory.createXMLStreamReader(buffer.toByteArray().inputStream())
            var inDefaults = false
            try {
                while (reader.hasNext()) {
                    context.checkActive()
                    when (reader.next()) {
                        XMLStreamConstants.START_ELEMENT -> {
                            if (reader.namespaceURI == WORD && reader.localName == "rPrDefault") inDefaults = true
                            if (reader.namespaceURI == WORD && reader.localName == "rFonts") {
                                repeat(reader.attributeCount) { index ->
                                    if (reader.getAttributeNamespace(index) == WORD) {
                                        val attribute = reader.getAttributeLocalName(index)
                                        if (attribute.endsWith("theme", true)) unsupported("Strict font processing requires explicit fonts instead of theme references")
                                        if (attribute in setOf("ascii", "hAnsi", "eastAsia", "cs")) {
                                            names.add(reader.getAttributeValue(index))
                                            if (inDefaults && attribute == "ascii") defaultAscii = true
                                            if (inDefaults && attribute == "hAnsi") defaultAnsi = true
                                        }
                                    }
                                }
                            }
                            if (reader.namespaceURI == WORD && reader.localName == "sym") names.add(reader.getAttributeValue(WORD, "font") ?: fail("Symbol font is missing"))
                            if (reader.namespaceURI == DRAWING && reader.localName in setOf("latin", "ea", "cs")) {
                                val name = reader.getAttributeValue(null, "typeface") ?: fail("Drawing font is missing")
                                if (name.isBlank() || name.startsWith('+')) unsupported("Strict font processing requires explicit drawing font families")
                                names.add(name)
                            }
                            if (reader.namespaceURI == "urn:schemas-microsoft-com:vml" && reader.localName == "textpath") unsupported("Strict VML text-path font resolution is not supported")
                            if (names.size > 128 || names.any { it.isBlank() || it.length > 128 }) fail("Invalid or excessive template font declarations")
                        }
                        XMLStreamConstants.END_ELEMENT -> if (reader.namespaceURI == WORD && reader.localName == "rPrDefault") inDefaults = false
                    }
                }
            } finally { reader.close() }
        }
        if (!defaultAscii || !defaultAnsi) unsupported("Strict font processing requires explicit ascii and hAnsi document defaults")
        return names
    }

    private fun embed(document: WordprocessingMLPackage, font: FontPolicy.FontResource, bytes: ByteArray) {
        val main = document.mainDocumentPart
        val table = main.fontTablePart ?: FontTablePart().also { it.jaxbElement = Fonts(); main.addTargetPart(it) }
        val entry = table.jaxbElement.font.singleOrNull { it.name == font.family } ?: Fonts.Font().also { it.name = font.family; table.jaxbElement.font.add(it) }
        val existing = when (font.face) { "bold" -> entry.embedBold; "italic" -> entry.embedItalic; "boldItalic" -> entry.embedBoldItalic; else -> entry.embedRegular }
        if (existing != null) unsupported("Replacing an existing embedded font face is not supported")
        val metadata = OpenTypeMetadata.read(bytes)
        entry.panose1 = FontPanose().apply { `val` = metadata.panose }
        entry.charset = CTUcharHexNumber().apply { `val` = if (metadata.codePageRanges[0].toLong(16) and 0x80000000L != 0L) "02" else "00" }
        entry.family = FontFamily().apply { `val` = "auto" }
        entry.sig = FontSig().apply {
            usb0 = metadata.unicodeRanges[0]; usb1 = metadata.unicodeRanges[1]; usb2 = metadata.unicodeRanges[2]; usb3 = metadata.unicodeRanges[3]
            csb0 = metadata.codePageRanges[0]; csb1 = metadata.codePageRanges[1]
        }
        val key = "{${UUID.randomUUID().toString().uppercase(java.util.Locale.ROOT)}}"
        var name: PartName
        do { name = PartName("/word/fonts/nf-${UUID.randomUUID()}.odttf") } while (document.parts.parts.containsKey(name))
        val part = ObfuscatedFontPart(name)
        part.setBinaryData(AbstractFontPart.obfuscate(key, bytes))
        val relationship = table.addTargetPart(part)
        val reference = FontRel().apply { id = relationship.id; fontKey = key; isSubsetted = false }
        when (font.face) { "bold" -> entry.embedBold = reference; "italic" -> entry.embedItalic = reference; "boldItalic" -> entry.embedBoldItalic = reference; else -> entry.embedRegular = reference }
    }

    private fun fail(message: String): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST, message)
    private fun unsupported(message: String): Nothing = DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_OPTION, message)
}

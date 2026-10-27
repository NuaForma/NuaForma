package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.docx.internal.BoundedStreams
import dev.nuaforma.docx.internal.DocxFailure
import dev.nuaforma.docx.internal.TemporaryPackage
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.namespace.QName
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamException
import javax.xml.stream.events.StartElement

/** Detach only validated passive references, before a third-party loader sees the private spool. */
internal object DetachedTemplate {
    fun strip(spool: Path, context: GenerationContext) {
        TemporaryPackage.create(context).use { temporary ->
            ZipFile(spool.toFile()).use { source ->
                ZipOutputStream(BoundedStreams.Output(Files.newOutputStream(temporary.path), context.limits.maxTemplateBytes, context)).use { output ->
                    source.entries().asSequence().forEach { entry ->
                        context.checkActive()
                        output.putNextEntry(ZipEntry(entry.name))
                        source.getInputStream(entry).use { input ->
                            if (entry.name in setOf(PassiveTemplateReferences.SETTINGS, PassiveTemplateReferences.RELATIONSHIPS))
                                copyXml(entry.name, input, output, context)
                            else BoundedStreams.copy(input, output, context.limits.maxExpandedBytes, context)
                        }
                        output.closeEntry()
                    }
                }
            }
            Files.copy(temporary.path, spool, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun copyXml(part: String, input: InputStream, output: OutputStream, context: GenerationContext) {
        val factory = XMLInputFactory.newDefaultFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setXMLResolver { _, _, _, _ -> throw XMLStreamException("External XML resolution is disabled") }
        }
        try {
            val reader = factory.createXMLEventReader(input)
            val writer = XMLOutputFactory.newDefaultFactory().createXMLEventWriter(output, "UTF-8")
            var omittedDepth = 0
            try {
                while (reader.hasNext()) {
                    context.checkActive()
                    val event = reader.nextEvent()
                    if (event.isStartElement && (omittedDepth > 0 || omit(part, event.asStartElement()))) omittedDepth++
                    else if (event.isEndElement && omittedDepth > 0) omittedDepth--
                    else if (omittedDepth == 0) writer.add(event)
                }
                writer.flush()
            } finally { try { reader.close() } finally { writer.close() } }
        } catch (_: XMLStreamException) {
            DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.DOCX_STRUCTURED_XML_CANNOT_BE_READ_REPAIR_THE_DOCUMENT)
        }
    }

    private fun omit(part: String, element: StartElement): Boolean = when (part) {
        PassiveTemplateReferences.SETTINGS -> element.name.namespaceURI == "http://schemas.openxmlformats.org/wordprocessingml/2006/main" &&
            element.name.localPart in setOf("attachedTemplate", "linkStyles")
        else -> element.name == QName("http://schemas.openxmlformats.org/package/2006/relationships", "Relationship") &&
            element.getAttributeByName(QName("Type"))?.value == "http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate"
    }
}

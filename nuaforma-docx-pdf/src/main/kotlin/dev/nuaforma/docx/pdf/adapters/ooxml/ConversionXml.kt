package dev.nuaforma.docx.pdf.adapters.ooxml

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.internal.PdfFailure
import java.io.ByteArrayOutputStream
import javax.xml.namespace.QName
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

internal sealed interface XmlNode
internal data class XmlText(val text: String) : XmlNode
internal data class XmlComment(val text: String) : XmlNode
internal class XmlElement(
    val name: QName,
    val attributes: MutableMap<QName, String> = linkedMapOf(),
    val namespaces: MutableMap<String, String> = linkedMapOf(),
    val children: MutableList<XmlNode> = mutableListOf(),
) : XmlNode {
    fun attribute(namespace: String, local: String): String? = attributes[QName(namespace, local)]
    fun elements(): List<XmlElement> = children.filterIsInstance<XmlElement>()
    fun descendants(): Sequence<XmlElement> = sequence {
        yield(this@XmlElement)
        for (element in elements()) yieldAll(element.descendants())
    }
}

/** Namespace-aware conversion-copy parser. No DTD, external resolution, PI or unbounded trees. */
internal class ConversionXml(private val context: GenerationContext) {
    private var nodes = 0L

    fun read(bytes: ByteArray): XmlElement {
        if (bytes.size > minOf(32L * 1024 * 1024, context.limits.maxExpandedBytes)) {
            PdfFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.CONVERSION_XML_BYTE_BUDGET_WAS_EXCEEDED)
        }
        val factory = XMLInputFactory.newDefaultFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setXMLResolver { _, _, _, _ -> invalid() }
        }
        val stack = java.util.ArrayDeque<XmlElement>()
        var root: XmlElement? = null
        try {
            val reader = factory.createXMLStreamReader(bytes.inputStream())
            try {
                while (reader.hasNext()) {
                    context.checkActive()
                    val event = reader.next()
                    if (event in setOf(XMLStreamConstants.START_ELEMENT, XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE, XMLStreamConstants.COMMENT)) {
                        if (++nodes > minOf(1_000_000L, context.limits.maxExpandedBytes / 32)) {
                            PdfFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.CONVERSION_XML_STRUCTURE_BUDGET_WAS_EXCEEDED)
                        }
                    }
                    when (event) {
                        XMLStreamConstants.START_ELEMENT -> {
                            if (stack.size >= 128) {
                                PdfFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.CONVERSION_XML_STRUCTURE_BUDGET_WAS_EXCEEDED)
                            }
                            val element = XmlElement(reader.name)
                            repeat(reader.attributeCount) { element.attributes[reader.getAttributeName(it)] = reader.getAttributeValue(it) }
                            repeat(reader.namespaceCount) { element.namespaces[reader.getNamespacePrefix(it) ?: ""] = reader.getNamespaceURI(it) ?: "" }
                            if (stack.isEmpty()) {
                                if (root != null) invalid()
                                root = element
                            } else stack.last.children.add(element)
                            stack.addLast(element)
                        }
                        XMLStreamConstants.END_ELEMENT -> {
                            if (stack.isEmpty() || stack.removeLast().name != reader.name) invalid()
                        }
                        XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                            if (stack.isNotEmpty()) stack.last.children.add(XmlText(reader.text))
                            else if (!reader.isWhiteSpace) invalid()
                        }
                        XMLStreamConstants.COMMENT -> if (stack.isNotEmpty()) stack.last.children.add(XmlComment(reader.text))
                        XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE, XMLStreamConstants.PROCESSING_INSTRUCTION -> invalid()
                    }
                }
            } finally { reader.close() }
        } catch (_: XMLStreamException) { invalid() }
        return root ?: invalid()
    }

    fun write(root: XmlElement): ByteArray {
        val output = ByteArrayOutputStream()
        val writer = XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(output, "UTF-8")
        fun append(node: XmlNode) {
            context.checkActive()
            when (node) {
                is XmlText -> writer.writeCharacters(node.text)
                is XmlComment -> writer.writeComment(node.text)
                is XmlElement -> {
                    writer.writeStartElement(node.name.prefix, node.name.localPart, node.name.namespaceURI)
                    for ((prefix, namespace) in node.namespaces) {
                        if (prefix.isEmpty()) writer.writeDefaultNamespace(namespace) else writer.writeNamespace(prefix, namespace)
                    }
                    for ((name, value) in node.attributes) {
                        if (name.namespaceURI.isEmpty()) writer.writeAttribute(name.localPart, value)
                        else writer.writeAttribute(name.prefix, name.namespaceURI, name.localPart, value)
                    }
                    node.children.forEach(::append)
                    writer.writeEndElement()
                }
            }
            if (output.size() > minOf(32L * 1024 * 1024, context.limits.maxExpandedBytes)) {
                PdfFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.CONVERSION_XML_BYTE_BUDGET_WAS_EXCEEDED)
            }
        }
        try {
            writer.writeStartDocument("UTF-8", "1.0")
            append(root)
            writer.writeEndDocument()
            writer.flush()
        } finally { writer.close() }
        return output.toByteArray()
    }

    private fun invalid(): Nothing = PdfFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.CONVERSION_COPY_CONTAINS_UNSUPPORTED_OR_MALFORMED_XML)
}

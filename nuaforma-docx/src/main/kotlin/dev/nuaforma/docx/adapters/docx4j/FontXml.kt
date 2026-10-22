package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.DocxFailure
import javax.xml.namespace.QName
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/** Private font-inspection projection, using the existing permitted JDK StAX boundary. */
internal class FontXmlElement(val namespaceURI: String, val localName: String, val parentNode: FontXmlElement?) {
    val attributes = mutableMapOf<QName, String>()
    val children = mutableListOf<FontXmlElement>()
    val text = StringBuilder()
    val textContent: String get() = text.toString()
    fun getAttribute(name: String): String = getAttributeNS("", name)
    fun getAttributeNS(namespace: String, name: String): String = attributes[QName(namespace, name)] ?: ""
    fun descendants(): Sequence<FontXmlElement> = sequence {
        yield(this@FontXmlElement)
        for (child in children) yieldAll(child.descendants())
    }
    fun elements(namespace: String, name: String): List<FontXmlElement> = descendants().filter { it.namespaceURI == namespace && it.localName == name }.toList()
}

internal class FontXml(private val context: GenerationContext) {
    private var nodes = 0L
    fun read(bytes: ByteArray): FontXmlElement {
        val factory = XMLInputFactory.newDefaultFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setXMLResolver { _, _, _, _ -> invalid() }
        }
        val stack = java.util.ArrayDeque<FontXmlElement>()
        var root: FontXmlElement? = null
        try {
            val reader = factory.createXMLStreamReader(bytes.inputStream())
            try {
                while (reader.hasNext()) {
                    context.checkActive()
                    when (reader.next()) {
                        XMLStreamConstants.START_ELEMENT -> root = start(reader, stack, root)
                        XMLStreamConstants.END_ELEMENT -> if (stack.isEmpty()) invalid() else stack.removeLast()
                        XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                            structure(true)
                            stack.peekLast()?.text?.append(reader.text)
                        }
                        XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE, XMLStreamConstants.PROCESSING_INSTRUCTION -> invalid()
                    }
                }
            } finally { reader.close() }
        } catch (_: XMLStreamException) { invalid() }
        return root ?: invalid()
    }

    private fun start(reader: XMLStreamReader, stack: java.util.ArrayDeque<FontXmlElement>, root: FontXmlElement?): FontXmlElement {
        structure(stack.size < 128)
        val element = FontXmlElement(reader.namespaceURI ?: "", reader.localName, stack.peekLast())
        repeat(reader.attributeCount) { element.attributes[reader.getAttributeName(it)] = reader.getAttributeValue(it) }
        if (stack.isEmpty() && root != null) invalid()
        stack.peekLast()?.children?.add(element)
        stack.addLast(element)
        return root ?: element
    }

    private fun structure(valid: Boolean) {
        if (!valid || ++nodes > minOf(1_000_000L, context.limits.maxExpandedBytes / 32))
            DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.INVALID_OR_EXCESSIVE_TEMPLATE_FONT_DECLARATIONS)
    }
    private fun invalid(): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.DOCX_FONTS_CANNOT_BE_INSPECTED_SAFELY)
}

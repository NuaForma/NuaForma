package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.PdfDrawingLayer
import dev.nuaforma.pdf.PdfDrawingOrder

/** Resolve print order separately from editing identifiers and anchor geometry. */
internal object WordDrawingOrder {
    private const val wp = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val drawing = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val picture = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    private const val shape = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
    private const val wp14 = "http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing"
    private val flags = setOf("simplePos", "behindDoc", "locked", "layoutInCell", "allowOverlap")
    private val distances = setOf("distT", "distB", "distL", "distR")
    private val attributes = flags + distances + setOf("relativeHeight", "hidden")

    fun validateSource(root: XmlElement, context: GenerationContext, reject: (XmlElement, String) -> Nothing) {
        for (node in root.descendants()) {
            context.checkActive()
            if (node.name.namespaceURI == wp && node.name.localPart == "anchor") validate(node, reject)
        }
    }

    fun validate(anchor: XmlElement, reject: (XmlElement, String) -> Nothing) {
        validateAttributes(anchor, reject)
        validatePolicy(anchor, reject)
        val properties = requiredChild(anchor, "docPr", reject)
        unsigned(properties, "id", 4_294_967_295L, reject)
        val simple = requiredChild(anchor, "simplePos", reject)
        validateSimplePosition(simple, reject)
        validateRelativeSizes(anchor, reject)
    }

    private fun requiredChild(anchor: XmlElement, name: String, reject: (XmlElement, String) -> Nothing): XmlElement {
        val values = anchor.elements().filter { it.name.namespaceURI == wp && it.name.localPart == name }
        return values.singleOrNull() ?: reject(values.getOrNull(1) ?: anchor, "Missing or duplicate original $name")
    }

    private fun validateAttributes(anchor: XmlElement, reject: (XmlElement, String) -> Nothing) {
        if (anchor.attributes.keys.any { key ->
                !(key.namespaceURI.isEmpty() && key.localPart in attributes ||
                    key.namespaceURI == wp14 && key.localPart in setOf("anchorId", "editId"))
            }) reject(anchor, "Unsupported original floating anchor attribute")
        for (key in setOf("anchorId", "editId")) anchor.attribute(wp14, key)?.let {
            if (!it.matches(Regex("[0-9a-fA-F]{8}"))) reject(anchor, "Invalid original drawing editing identifier")
        }
    }

    private fun validatePolicy(anchor: XmlElement, reject: (XmlElement, String) -> Nothing) {
        for (key in flags) if (anchor.attribute("", key) !in setOf("0", "1", "false", "true"))
            reject(anchor, "Invalid original floating anchor Boolean")
        if (anchor.attribute("", "hidden") !in setOf(null, "0", "false"))
            reject(anchor, "Hidden floating drawings are not qualified")
        unsigned(anchor, "relativeHeight", 4_294_967_295L, reject)
        for (key in distances) if (anchor.attribute("", key) != null) unsigned(anchor, key, 182_880_000L, reject)
    }

    private fun validateRelativeSizes(anchor: XmlElement, reject: (XmlElement, String) -> Nothing) {
        val graphic = anchor.elements().singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == "graphic" }
        val data = graphic?.elements()?.singleOrNull { it.name.namespaceURI == drawing && it.name.localPart == "graphicData" }
        val graphicObject = data?.elements()?.singleOrNull()
        val sizedObject = when (data?.attribute("", "uri")) {
            picture -> graphicObject?.name?.namespaceURI == picture && graphicObject.name.localPart == "pic"
            shape -> graphicObject?.name?.namespaceURI == shape && graphicObject.name.localPart == "wsp" &&
                graphicObject.elements().count { it.name.namespaceURI == shape && it.name.localPart == "txbx" } == 1
            else -> false
        }
        val seen = mutableSetOf<javax.xml.namespace.QName>()
        for (node in anchor.elements()) {
            if (node.name.localPart in setOf("sizeRelH", "sizeRelV") && node.name.namespaceURI != wp14)
                reject(node, "Invalid original relative-size namespace")
            if (node.name.namespaceURI == wp14) {
                if (!seen.add(node.name)) reject(node, "Duplicate original relative-size metadata")
                val relative = WordAnchorMetadata.read(node) { message -> reject(node, message) }
                if (relative != null && !sizedObject) reject(node, "Nonzero relative sizing requires an embedded picture or text frame")
            }
        }
    }

    fun validateSimplePosition(node: XmlElement, reject: (XmlElement, String) -> Nothing) {
        if (node.name.namespaceURI != wp || node.name.localPart != "simplePos" || node.attributes.size != 2 ||
            node.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart !in setOf("x", "y") } ||
            node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(node, "Invalid original simple-position structure")
        for (key in listOf("x", "y")) {
            val raw = node.attribute("", key).orEmpty()
            val value = raw.toLongOrNull()
            if (!raw.matches(Regex("-?[0-9]{1,10}")) || value == null || value !in -182_880_000L..182_880_000L)
                reject(node, "Invalid original simplePos/@$key")
        }
    }

    fun read(anchor: XmlElement, separatedFooter: Boolean = false, reject: (XmlElement, String) -> Nothing): PdfDrawingOrder {
        validate(anchor, reject)
        if (anchor.attribute("", "simplePos") !in setOf("0", "false") || !separatedFooter && anchor.attribute("", "allowOverlap") !in setOf("1", "true"))
            reject(anchor, "Automatic floating anchor relocation is not qualified")
        val properties = anchor.elements().single { it.name.namespaceURI == wp && it.name.localPart == "docPr" }
        val layer = if (anchor.attribute("", "behindDoc") in setOf("1", "true")) PdfDrawingLayer.BACKGROUND else PdfDrawingLayer.FOREGROUND
        return PdfDrawingOrder(layer, unsigned(anchor, "relativeHeight", 4_294_967_295L, reject), unsigned(properties, "id", 4_294_967_295L, reject))
    }

    private fun unsigned(node: XmlElement, key: String, maximum: Long, reject: (XmlElement, String) -> Nothing): Long {
        val raw = node.attribute("", key).orEmpty()
        val value = raw.toLongOrNull()
        if (!raw.matches(Regex("[0-9]{1,10}")) || value == null || value !in 0L..maximum)
            reject(node, "Invalid original drawing order or distance")
        return value
    }

}

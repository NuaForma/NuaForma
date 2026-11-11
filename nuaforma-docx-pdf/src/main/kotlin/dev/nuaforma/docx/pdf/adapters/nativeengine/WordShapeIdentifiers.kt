package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText

/** Only shape-ID allocation metadata is inert when existing VML objects are present. */
internal class WordShapeIdentifiers(private val context: GenerationContext) {
    private val office = "urn:schemas-microsoft-com:office:office"
    private val vml = "urn:schemas-microsoft-com:vml"

    fun validate(root: XmlElement, reject: () -> Nothing) {
        if (root.attributes.isNotEmpty() || !whitespace(root)) reject()
        val seen = mutableSetOf<String>()
        for (child in root.elements()) {
            context.checkActive()
            if (child.name.namespaceURI != office || !seen.add(child.name.localPart)) reject()
            when (child.name.localPart) {
                "shapedefaults" -> {
                    attributes(child, setOf("spidmax"), reject)
                    if (child.elements().isNotEmpty()) reject()
                    child.attribute("", "spidmax")?.let { if (!identifier(it)) reject() }
                }
                "shapelayout" -> {
                    attributes(child, emptySet(), reject)
                    if (child.elements().size > 1) reject()
                    for (map in child.elements()) {
                        if (map.name.namespaceURI != office || map.name.localPart != "idmap") reject()
                        attributes(map, setOf("data"), reject)
                        if (map.elements().isNotEmpty()) reject()
                        map.attribute("", "data")?.let { data ->
                            if (data.length > 703) reject()
                            val ids = data.split(',')
                            if (ids.size > 64 || ids.any { !identifier(it) } || ids.map { it.toLong() }.distinct().size != ids.size) reject()
                        }
                    }
                }
                else -> reject()
            }
        }
    }

    private fun attributes(node: XmlElement, names: Set<String>, reject: () -> Nothing) {
        if (!whitespace(node) || node.attributes.keys.any {
            !(it.namespaceURI.isEmpty() && it.localPart in names || it.namespaceURI == vml && it.localPart == "ext")
        } || node.attribute(vml, "ext") !in setOf(null, "edit")) reject()
    }

    private fun identifier(value: String) = value.matches(Regex("[0-9]{1,10}")) && value.toLongOrNull() in 0L..2_147_483_647L

    private fun whitespace(node: XmlElement) = node.children.filterIsInstance<XmlText>().all { it.text.isBlank() }
}

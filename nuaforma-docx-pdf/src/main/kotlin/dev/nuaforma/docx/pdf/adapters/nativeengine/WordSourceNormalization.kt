package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import javax.xml.namespace.QName

/** Attribute-only canonicalization retains the original package, including review metadata. */
internal data class WordSourceNormalization(
    val part: String,
    val route: List<Pair<QName, Int>>,
    val attributes: Map<QName, String>,
) {
    companion object {
        fun observe(root: XmlElement, part: String, source: NativeDocxSource): () -> List<WordSourceNormalization> {
            val originals = root.descendants().map { node ->
                Triple(node, source.routeOf(node), node.attributes.toMap())
            }.toList()
            return {
                originals.mapNotNull { (node, route, before) ->
                    val changes = node.attributes.filter { (name, value) -> before[name] != value }
                    if (changes.isEmpty()) null else WordSourceNormalization(part, route, changes)
                }
            }
        }
    }
}

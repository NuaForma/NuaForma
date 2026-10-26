package dev.nuaforma.pdf

import java.util.Collections

/** Logical roles, independent of PDF tag names and physical drawing order. */
enum class PdfSemanticRole {
    DOCUMENT, SECTION, PARAGRAPH, HEADING, CONTENTS, CONTENTS_ENTRY, CAPTION,
    FIGURE, TABLE, TABLE_ROW, TABLE_CELL, TABLE_HEADER_CELL, FRAME, HEADER, FOOTER, TEXT, LABEL, REFERENCE, LINK, NOTE, NOTE_REFERENCE,
}

/** Stable occurrence identity; a caption association is supplied explicitly, never guessed from proximity. */
data class PdfBlockSemantics @JvmOverloads constructor(
    val id: String, val role: PdfSemanticRole = PdfSemanticRole.PARAGRAPH,
    val level: Int? = null, val parentId: String? = null, val captionTargetId: String? = null,
) {
    init {
        requireSemanticId(id)
        parentId?.let(::requireSemanticId)
        captionTargetId?.let(::requireSemanticId)
        require(level == null || role in setOf(PdfSemanticRole.HEADING, PdfSemanticRole.CONTENTS_ENTRY) && level in 0..8) { "Invalid semantic heading level" }
        require(captionTargetId == null || role == PdfSemanticRole.CAPTION) { "Caption association requires caption semantics" }
    }
}

data class PdfSpanSemantics @JvmOverloads constructor(
    val id: String, val role: PdfSemanticRole = PdfSemanticRole.TEXT, val target: String? = null,
) {
    init {
        requireSemanticId(id)
        require(role in setOf(PdfSemanticRole.TEXT, PdfSemanticRole.LABEL, PdfSemanticRole.REFERENCE, PdfSemanticRole.LINK, PdfSemanticRole.NOTE_REFERENCE)) { "Invalid inline semantic role" }
        target?.let(::requireSemanticId)
    }
}

/** One logical node can own multiple final text/image fragments and link annotations. */
data class PdfSemanticNode @JvmOverloads constructor(
    val id: String, val role: PdfSemanticRole, val parentId: String?, val level: Int? = null,
    val targetId: String? = null, val text: String? = null, val referenceName: String? = null,
)

/** Inspection-ready logical hierarchy; this is not a PDF structure tree or a level-A claim. */
class PdfLogicalStructure(nodes: List<PdfSemanticNode>) {
    val nodes: List<PdfSemanticNode> = Collections.unmodifiableList(ArrayList(nodes))
    init {
        require(this.nodes.size <= 12_000) { "Logical node budget exceeded" }
        require(this.nodes.sumOf { it.text?.length?.toLong() ?: 0L } <= 400_000) { "Logical text budget exceeded" }
        val known = mutableSetOf<String>()
        for (node in this.nodes) {
            requireSemanticId(node.id)
            require(known.add(node.id)) { "Duplicate logical node identity" }
            require(node.parentId == null || node.parentId != node.id && node.parentId in known) { "Missing or unordered logical parent" }
        }
        require(this.nodes.all { it.targetId == null || it.targetId in known }) { "Missing logical association target" }
    }
}

private val semanticIdentity = Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}")
internal fun requireSemanticId(id: String) { require(semanticIdentity.matches(id)) { "Invalid logical identity" } }

internal fun semanticChildId(parent: String, suffix: String): String {
    val value = "$parent-$suffix"
    if (value.length <= 128) return value
    val hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    return parent.take(95) + "-" + hash.take(32)
}

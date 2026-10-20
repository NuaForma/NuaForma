package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.internal.DocxFailure
import jakarta.xml.bind.JAXBElement
import org.docx4j.TraversalUtil
import org.docx4j.XmlUtils
import org.docx4j.dml.wordprocessingDrawing.Anchor
import org.docx4j.dml.wordprocessingDrawing.Inline
import org.docx4j.vml.CTShape
import org.docx4j.vml.VmlAllCoreAttributes
import org.docx4j.wml.*
import java.math.BigInteger

/** Clone-safe identifiers within one repeated element; cross-element ranges fail rather than detach. */
internal class WordIdentities(private val context: GenerationContext) {
    private var bookmark = BigInteger.ZERO
    private var drawing = 0L
    private var serial = 0L
    private val names = mutableSetOf<String>()
    private val shapes = mutableSetOf<String>()
    private val extensionIds = mutableSetOf<String>()
    private val bookmarkLinks = mutableMapOf<String, Int>()
    private var referenceFields = false

    fun boundary(root: Any, repeated: Boolean, original: Boolean = true) {
        val starts = mutableListOf<BigInteger>()
        val ends = mutableListOf<BigInteger>()
        val localNames = mutableListOf<String>()
        val localLinks = mutableMapOf<String, Int>()
        walk(root) { raw, node ->
            val name = (raw as? JAXBElement<*>)?.name?.localPart
            if (node is CTBookmark) { starts.add(node.id); localNames.add(node.name) }
            if (node is P.Hyperlink) node.anchor?.let { localLinks[it] = (localLinks[it] ?: 0) + 1 }
            if (name == "bookmarkEnd" && node is CTMarkupRange) ends.add(node.id)
            if ((node is P && node.pPr?.sectPr != null) || (node is Tc && node.tcPr?.vMerge != null) ||
                name in setOf("commentRangeStart", "commentRangeEnd", "commentReference", "permStart", "permEnd") ||
                (repeated && (node is SdtElement || node is FldChar || node is CTSimpleField || node is CTTrackChange ||
                    (node is VmlAllCoreAttributes && (node !is CTShape || node.spid != null)) ||
                    name in setOf("footnoteReference", "endnoteReference")))) {
                fail(RejectionReason.DYNAMIC_BLOCKS_CANNOT_DETACH_RANGES_OR_REPEAT_FIELDS_ANNOTATIONS)
            }
        }
        if (starts.size != starts.distinct().size || starts.sorted() != ends.sorted()) fail(RejectionReason.EACH_DYNAMIC_ELEMENT_MUST_CONTAIN_COMPLETE_BOOKMARK_RANGES)
        if (original && localNames.any { referenceFields || (bookmarkLinks[it] ?: 0) > (localLinks[it] ?: 0) }) {
            fail(RejectionReason.DYNAMIC_BOOKMARKS_CANNOT_HAVE_INCOMING_LINKS_FROM_OUTSIDE_THEIR)
        }
    }

    fun collect(root: Any) = walk(root) { raw, node ->
        val instruction = when {
            node is CTSimpleField -> node.instr
            node is Text && (raw as? JAXBElement<*>)?.name?.localPart == "instrText" -> node.value
            else -> null
        }
        if (instruction != null && Regex("(?i)\\b(?:REF|PAGEREF|NOTEREF)\\b").containsMatchIn(instruction)) referenceFields = true
        when (node) {
        is CTBookmark -> { bookmark = maxOf(bookmark, node.id); names.add(node.name) }
        is P.Hyperlink -> node.anchor?.let { bookmarkLinks[it] = (bookmarkLinks[it] ?: 0) + 1 }
        is P -> { node.paraId?.let { extensionIds.add(it.lowercase(java.util.Locale.ROOT)) }; node.textId?.let { extensionIds.add(it.lowercase(java.util.Locale.ROOT)) } }
        is Inline -> { drawing = maxOf(drawing, node.docPr?.id ?: 0); remember(node.anchorId); remember(node.editId) }
        is Anchor -> { drawing = maxOf(drawing, node.docPr?.id ?: 0); remember(node.anchorId); remember(node.editId) }
        is VmlAllCoreAttributes -> node.vmlId?.let { shapes.add(it) }
    } }

    fun renew(root: Any) {
        boundary(root, true, false)
        val starts = mutableMapOf<BigInteger, CTBookmark>()
        val ends = mutableMapOf<BigInteger, CTMarkupRange>()
        walk(root) { raw, node ->
            val name = (raw as? JAXBElement<*>)?.name?.localPart
            if (name in setOf("commentRangeStart", "commentRangeEnd", "commentReference", "footnoteReference", "endnoteReference", "permStart", "permEnd") ||
                node is SdtElement || node is FldChar || node is CTSimpleField ||
                (node is P && node.pPr?.sectPr != null) ||
                (node is Tc && node.tcPr?.vMerge != null)) {
                fail(RejectionReason.REPEATED_CONTENT_CANNOT_CONTAIN_ANNOTATIONS_FIELDS_CONTENT_CONTROLS_SECTION)
            }
            when {
                node is CTBookmark -> { if (starts.put(node.id, node) != null) fail(RejectionReason.REPEATED_BOOKMARK_IDS_ARE_AMBIGUOUS) }
                name == "bookmarkEnd" && node is CTMarkupRange -> { if (ends.put(node.id, node) != null) fail(RejectionReason.REPEATED_BOOKMARK_ENDS_ARE_AMBIGUOUS) }
                node is Inline -> { node.docPr?.let { it.id = nextDrawing() }; if (node.anchorId != null) node.anchorId = nextExtension(); if (node.editId != null) node.editId = nextExtension() }
                node is Anchor -> { node.docPr?.let { it.id = nextDrawing() }; if (node.anchorId != null) node.anchorId = nextExtension(); if (node.editId != null) node.editId = nextExtension() }
                node is P -> {
                    if (node.paraId != null) node.paraId = java.util.HexFormat.of().withUpperCase().formatHex(nextExtension())
                    if (node.textId != null) node.textId = java.util.HexFormat.of().withUpperCase().formatHex(nextExtension())
                }
                node is CTShape -> {
                    if (node.spid != null) fail(RejectionReason.REPEATED_VML_SHAPES_WITH_OFFICE_SPECIFIC_SHAPE_IDS_ARE)
                    var id: String
                    do { id = "NuaFormaShape${++serial}" } while (!shapes.add(id))
                    node.vmlId = id
                }
            }
        }
        if (starts.keys != ends.keys) fail(RejectionReason.EACH_REPEATED_ELEMENT_MUST_CONTAIN_COMPLETE_BOOKMARK_RANGES)
        val renamed = mutableMapOf<String, String>()
        starts.forEach { (id, start) ->
            bookmark += BigInteger.ONE
            start.id = bookmark; ends.getValue(id).id = bookmark
            var name: String
            do { name = "NF${++serial}" } while (!names.add(name))
            renamed[start.name] = name; start.name = name
        }
        walk(root) { _, node -> if (node is P.Hyperlink) renamed[node.anchor]?.let { node.anchor = it } }
    }

    fun nextDrawing(): Long {
        if (drawing >= 0xFFFF_FFFFL) fail(RejectionReason.NO_FREE_DRAWING_IDENTIFIERS_REMAIN)
        return ++drawing
    }

    private fun remember(value: ByteArray?) { value?.let { extensionIds.add(java.util.HexFormat.of().formatHex(it)) } }
    private fun nextExtension(): ByteArray {
        while (serial < 0x7FFF_FFFFL) {
            val bytes = java.nio.ByteBuffer.allocate(4).putInt((++serial).toInt()).array()
            if (extensionIds.add(java.util.HexFormat.of().formatHex(bytes))) return bytes
        }
        fail(RejectionReason.NO_FREE_DRAWING_EXTENSION_IDENTIFIERS_REMAIN)
    }

    private fun walk(root: Any, action: (Any, Any) -> Unit) {
        fun visit(raw: Any, depth: Int) {
            context.checkActive()
            if (depth > 128) DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.DOCX_IDENTITY_TRAVERSAL_IS_TOO_DEEP)
            val node = XmlUtils.unwrap(raw)
            action(raw, node)
            val graphic = when (node) { is Inline -> node.graphic; is Anchor -> node.graphic; else -> null }
            val children = graphic?.graphicData?.wordprocessingShape?.txbx?.txbxContent?.content
                ?: TraversalUtil.getChildrenImpl(node) ?: emptyList()
            children.forEach { visit(it, depth + 1) }
        }
        visit(root, 1)
    }
    private fun fail(reason: RejectionReason): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, reason)
}

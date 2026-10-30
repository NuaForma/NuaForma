package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.DocxFailure
import org.docx4j.XmlUtils
import org.docx4j.TraversalUtil
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.wml.*

/** Word's print-view hidden-text state; all caches belong to this document. */
internal class WordVisibility(document: WordprocessingMLPackage, private val context: GenerationContext) {
    private val root = document.mainDocumentPart.styleDefinitionsPart?.jaxbElement
    private val styles = root?.style.orEmpty().associateBy { it.styleId }
    private val defaults = root?.docDefaults?.rPrDefault?.rPr?.vanish?.isVal ?: false
    private val paragraphDefault = root?.style?.firstOrNull { it.type == "paragraph" && it.isDefault }?.styleId
    private val characterDefault = root?.style?.firstOrNull { it.type == "character" && it.isDefault }?.styleId
    private val tableDefault = root?.style?.firstOrNull { it.type == "table" && it.isDefault }?.styleId
    private val inheritedFormatting = defaults || styles.values.any { style ->
        style.rPr?.vanish != null || style.tblStylePr.any { it.rPr?.vanish != null }
    }
    private val toggles = mutableMapOf<Pair<String, String>, Boolean>()

    fun hidden(run: R, paragraph: P?, table: Tbl? = null): Boolean {
        context.checkActive()
        if (!inheritedFormatting) return run.rPr?.vanish?.isVal ?: false
        val inherited = defaults xor toggle(table?.tblPr?.tblStyle?.`val` ?: tableDefault.takeIf { table != null }, "table") xor
            toggle(paragraph?.pPr?.pStyle?.`val` ?: paragraphDefault, "paragraph") xor
            toggle(run.rPr?.rStyle?.`val` ?: characterDefault, "character")
        return run.rPr?.vanish?.isVal ?: inherited
    }

    private fun toggle(id: String?, type: String): Boolean {
        if (id == null) return false
        return toggles.getOrPut(id to type) {
            var current: String? = id
            var hidden = false
            val seen = mutableSetOf<String>()
            while (current != null) {
                context.checkActive()
                if (!seen.add(current) || seen.size > 64) fail()
                val style = styles[current] ?: fail()
                if (style.type != type) fail()
                // Conditional table formatting needs a location-specific cascade; do not guess visibility.
                if (type == "table" && style.tblStylePr.any { it.rPr?.vanish != null }) fail()
                if (style.rPr?.vanish?.isVal == true) hidden = !hidden
                current = style.basedOn?.`val`
            }
            hidden
        }
    }

    /** PDF intermediates omit hidden runs before font collection and native reading. DOCX output keeps them. */
    fun removeHidden(root: Any) {
        var visits = 0
        fun walk(raw: Any, paragraph: P?, table: Tbl?, depth: Int) {
            context.checkActive()
            if (++visits > 1_000_000 || depth > 128) fail()
            val node = XmlUtils.unwrap(raw)
            val p = node as? P ?: paragraph
            val t = node as? Tbl ?: table
            val children = children(node)
            children.removeIf { child -> (XmlUtils.unwrap(child) as? R)?.let { hidden(it, p, t) } == true }
            children.toList().forEach { walk(it, p, t, depth + 1) }
        }
        walk(root, null, null, 1)
    }

    companion object {
        fun children(node: Any): MutableList<Any> {
            val graphic = when (node) {
                is org.docx4j.dml.wordprocessingDrawing.Inline -> node.graphic
                is org.docx4j.dml.wordprocessingDrawing.Anchor -> node.graphic
                else -> null
            }
            graphic?.graphicData?.wordprocessingShape?.txbx?.txbxContent?.let { return it.content }
            return TraversalUtil.getChildrenImpl(node) ?: mutableListOf()
        }
    }

    private fun fail(): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE,
        RejectionReason.DOCX_STRUCTURE_CANNOT_BE_TRANSFORMED_SAFELY_SIMPLIFY_THE_TEMPLATE)
}

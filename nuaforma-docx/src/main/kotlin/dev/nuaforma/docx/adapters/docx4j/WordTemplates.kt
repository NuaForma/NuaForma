package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.DocxFailure
import dev.nuaforma.docx.internal.WatermarkPolicy
import dev.nuaforma.template.TemplateSession
import dev.nuaforma.template.ContentTemplates
import dev.nuaforma.template.ContentTemplateSession
import dev.nuaforma.template.TemplatePipes
import org.docx4j.TraversalUtil
import org.docx4j.XmlUtils
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart
import org.docx4j.wml.*

internal class WordTemplates(data: Map<String, *>, options: GenerationOptions, private val context: GenerationContext, pipes: List<ContentPipe>, private val valuePipes: TemplatePipes) {
    private val root = ContentTemplates.open(data, pipes, valuePipes, options, context)
    private val watermark = options.watermark
    private val identities = WordIdentities(context)
    private var visits = 0L
    private lateinit var images: WordImages
    private lateinit var owner: org.docx4j.openpackaging.parts.Part

    fun apply(document: WordprocessingMLPackage): TextWatermark? {
        watermark?.let { root.validateText(it.text) }
        images = WordImages(document, identities, context)
        val parts = listOf(document.mainDocumentPart to document.mainDocumentPart.jaxbElement.body) + document.parts.parts.values.mapNotNull {
            when (it) { is HeaderPart -> it to it.jaxbElement; is FooterPart -> it to it.jaxbElement; else -> null }
        }
        val roots = parts.map { it.second }
        val unsupported = document.parts.parts.values.mapNotNull { when (it) {
            is org.docx4j.openpackaging.parts.WordprocessingML.FootnotesPart -> it.jaxbElement
            is org.docx4j.openpackaging.parts.WordprocessingML.EndnotesPart -> it.jaxbElement
            is org.docx4j.openpackaging.parts.WordprocessingML.CommentsPart -> it.jaxbElement
            else -> null
        } }
        (roots + unsupported).forEach { identities.collect(it) }
        unsupported.forEach { rejectUnsupportedContent(it, 1) }
        parts.forEach { (part, value) -> owner = part; process(value, root, true, 1) }
        parts.forEach { (part, value) -> owner = part; process(value, root, false, 1) }
        return watermark?.let { requested ->
            val text = StringBuilder()
            root.expand(requested.text).forEach { fragment ->
                if (fragment.text.length > 4096 - text.length) {
                    DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "Resolved watermark text exceeds 4096 characters")
                }
                text.append(fragment.text)
            }
            if (text.isBlank()) null else TextWatermark(text.toString(), requested.color, requested.opacity, requested.rotation)
                .also { WatermarkPolicy.validate(it) }
        }
    }

    private fun rejectUnsupportedContent(raw: Any, depth: Int) {
        context.checkActive()
        if (++visits > 1_000_000 || depth > 128) DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "DOCX structure exceeds processing limits")
        val node = XmlUtils.unwrap(raw)
        val content = children(node)
        if (node is P || node is P.Hyperlink) {
            val text = WordRuns.detectionSource(content)
            if (ContentTemplates.containsContentMarker(text, valuePipes, context)) invalid("Content pipes are unsupported in comments, footnotes or endnotes")
        }
        content.forEach { rejectUnsupportedContent(it, depth + 1) }
    }

    private fun process(raw: Any, session: ContentTemplateSession, validateOnly: Boolean, depth: Int, inlineHandled: Boolean = false) {
        context.checkActive()
        if (++visits > 1_000_000 || depth > 128) DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "DOCX structure exceeds processing limits")
        val node = XmlUtils.unwrap(raw)
        val children = children(node)
        if (validateOnly && node is R && WordRuns.plain(node) && !inlineHandled &&
            ContentTemplates.containsContentMarker(WordRuns.source(listOf(node)), valuePipes, context)) {
            invalid("Content pipes require ordinary paragraph or hyperlink text, not field results or content controls")
        }
        if (node is P || node is P.Hyperlink) WordRuns.apply(children, session, validateOnly, context, valuePipes) { images.drawing(it, owner) }
        if (node is R) WordRuns.mixed(node, session, validateOnly, context, valuePipes)
        if (node is Body || node is Hdr || node is Ftr || node is Tc || node is Tbl || node is CTTxbxContent ||
            node is org.docx4j.vml.CTTextbox || modernTextBox(node)) blocks(children, session, validateOnly, depth)
        else children.toList().forEach { process(it, session, validateOnly, depth + 1,
            (node is P || node is P.Hyperlink) && XmlUtils.unwrap(it) is R) }
        if (!validateOnly && node is Tc && (children.isEmpty() || XmlUtils.unwrap(children.last()) !is P)) children.add(P())
        if (!validateOnly && node is Tbl && children.none { XmlUtils.unwrap(it) is Tr }) invalid("Conditions or repeats removed every table row; wrap the whole table instead")
    }

    private fun blocks(content: MutableList<Any>, session: ContentTemplateSession, validateOnly: Boolean, depth: Int) {
        val plan = parse(content, session, depth)
        if (validateOnly) {
            fun validate(nodes: List<Block>, scope: ContentTemplateSession, dynamic: Boolean, repeated: Boolean) { nodes.forEach { when (it) {
                is Item -> { if (dynamic) identities.boundary(it.value, repeated); process(it.value, scope, true, depth + 1) }
                is Condition -> { scope.validateExpression(it.expression); val child = scope.branch(); validate(it.yes, child, true, repeated); validate(it.no, child, true, repeated) }
                is Repeat -> { scope.validateExpression(it.expression); validate(it.body, scope.branch(), true, true) }
            } } }
            validate(plan, session, false, false)
            return
        }
        fun emit(nodes: List<Block>, scope: ContentTemplateSession, clone: Boolean): List<Any> {
            val output = mutableListOf<Any>()
            nodes.forEach { block -> when (block) {
                is Item -> {
                    val value = if (clone) XmlUtils.deepCopy(block.value) else block.value
                    if (clone) identities.renew(value)
                    process(value, scope, false, depth + 1)
                    output.add(value)
                }
                is Condition -> output.addAll(emit(if (scope.test(block.expression)) block.yes else block.no, scope.branch(), clone))
                is Repeat -> scope.repeat(block.expression, block.alias).forEach { output.addAll(emit(block.body, it, true)) }
            } }
            return output
        }
        val output = emit(plan, session, false)
        content.clear(); content.addAll(output)
    }

    private sealed interface Block
    private class Item(val value: Any) : Block
    private class Condition(val expression: String, val yes: List<Block>, val no: List<Block>) : Block
    private class Repeat(val expression: String, val alias: String, val body: List<Block>) : Block

    private fun parse(content: List<Any>, session: TemplateSession, depth: Int): List<Block> {
        var cursor = 0
        fun sequence(stops: Set<String>, nesting: Int): Pair<List<Block>, String?> {
            if (nesting > context.limits.maxExpressionDepth || depth + nesting > 128) {
                DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "DOCX block nesting exceeds the configured limit")
            }
            val result = mutableListOf<Block>()
            while (cursor < content.size) {
                context.checkActive()
                val value = content[cursor++]
                val marker = marker(value)
                if (marker in stops) return result to marker
                when {
                    marker == null -> result.add(Item(value))
                    marker.startsWith("#if ") -> {
                        val expression = marker.substring(4)
                        session.validateExpression(expression)
                        val (yes, stop) = sequence(setOf("#else", "/if"), nesting + 1)
                        if (stop == null) invalid("Unclosed DOCX condition")
                        val no = if (stop == "#else") sequence(setOf("/if"), nesting + 1).also {
                            if (it.second == null) invalid("Unclosed DOCX condition")
                        }.first else emptyList()
                        result.add(Condition(expression, yes, no))
                    }
                    marker.startsWith("#each ") -> {
                        val match = Regex("(.+)\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*)").matchEntire(marker.substring(6))
                            ?: invalid("Invalid DOCX repeat marker")
                        val expression = match.groupValues[1]; val alias = match.groupValues[2]
                        if (alias in setOf("data", "loop", "true", "false", "null")) invalid("Reserved DOCX repeat alias")
                        session.validateExpression(expression)
                        val (body, stop) = sequence(setOf("/each"), nesting + 1)
                        if (stop == null) invalid("Unclosed DOCX repeat")
                        result.add(Repeat(expression, alias, body))
                    }
                    else -> invalid("Unexpected DOCX block marker")
                }
            }
            return result to null
        }
        return sequence(emptySet(), 1).first
    }

    private fun marker(raw: Any): String? {
        val node = XmlUtils.unwrap(raw)
        val paragraph = when (node) {
            is P -> node
            is Tr -> {
                val cells = node.content.map { XmlUtils.unwrap(it) }
                if (cells.size != 1 || cells[0] !is Tc) return null
                val paragraphs = (cells[0] as Tc).content.map { XmlUtils.unwrap(it) }
                if (paragraphs.size != 1 || paragraphs[0] !is P) return null
                paragraphs[0] as P
            }
            else -> return null
        }
        if (!paragraph.content.all { WordRuns.plain(it) }) return null
        val source = WordRuns.source(paragraph.content).trim()
        if (!source.startsWith('{') || !source.endsWith('}')) return null
        val marker = source.substring(1, source.length - 1).trim()
        if (!(marker.startsWith('#') || marker.startsWith('/')) || '{' in marker || '}' in marker) return null
        if (marker.length > 4096) DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "DOCX structural marker exceeds the language limit")
        if (paragraph.pPr?.sectPr != null) invalid("Block marker paragraphs cannot contain section breaks")
        return marker
    }

    private fun children(node: Any): MutableList<Any> {
        // The generic traversal skips the mutable modern text-box container; use its real content list.
        val graphic = when (node) {
            is org.docx4j.dml.wordprocessingDrawing.Inline -> node.graphic
            is org.docx4j.dml.wordprocessingDrawing.Anchor -> node.graphic
            else -> null
        }
        graphic?.graphicData?.wordprocessingShape?.txbx?.txbxContent?.let { return it.content }
        return TraversalUtil.getChildrenImpl(node) ?: mutableListOf()
    }

    private fun modernTextBox(node: Any): Boolean = when (node) {
        is org.docx4j.dml.wordprocessingDrawing.Inline -> node.graphic?.graphicData?.wordprocessingShape?.txbx != null
        is org.docx4j.dml.wordprocessingDrawing.Anchor -> node.graphic?.graphicData?.wordprocessingShape?.txbx != null
        else -> false
    }

    private fun invalid(message: String): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, message)
}

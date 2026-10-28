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

internal class WordTemplates(data: Map<String, *>, options: GenerationOptions, private val context: GenerationContext, pipes: List<ContentPipe>, private val valuePipes: TemplatePipes, private val fault: WordTemplateFault? = null) {
    private val root = ContentTemplates.open(data, pipes, valuePipes, options, context)
    private val watermark = options.watermark
    private val identities = WordIdentities(context)
    private var visits = 0L
    private lateinit var images: WordImages
    private lateinit var owner: org.docx4j.openpackaging.parts.Part
    private val paragraphs = java.util.IdentityHashMap<P, Int>()
    private var paragraphIndex = 0

    fun apply(document: WordprocessingMLPackage, inspection: Boolean = false): TextWatermark? {
        watermark?.let { root.validateText(it.text) }
        images = WordImages(document, identities, context)
        val parts = listOf(document.mainDocumentPart to document.mainDocumentPart.jaxbElement.body) + document.parts.parts.values.mapNotNull {
            when (it) { is HeaderPart -> it to it.jaxbElement; is FooterPart -> it to it.jaxbElement; else -> null }
        }
        val roots = parts.map { it.second }
        val unsupported = document.parts.parts.values.mapNotNull { when (it) {
            is org.docx4j.openpackaging.parts.WordprocessingML.FootnotesPart -> it to it.jaxbElement
            is org.docx4j.openpackaging.parts.WordprocessingML.EndnotesPart -> it to it.jaxbElement
            is org.docx4j.openpackaging.parts.WordprocessingML.CommentsPart -> it to it.jaxbElement
            else -> null
        } }
        (roots + unsupported.map { it.second }).forEach { identities.collect(it) }
        unsupported.forEach { (part, value) -> DocxFailure.inPart(part.getPartName().name.removePrefix("/")) { rejectUnsupportedContent(value, 1) } }
        parts.forEach { (part, value) -> DocxFailure.inPart(part.getPartName().name.removePrefix("/")) {
            paragraphIndex = 0
            if (context.logging.mode == LoggingMode.DEVELOPMENT) indexParagraphs(value, 1)
            owner = part
            process(value, root, true, 1)
        } }
        if (inspection) return null
        parts.forEach { (part, value) -> DocxFailure.inPart(part.getPartName().name.removePrefix("/")) { owner = part; process(value, root, false, 1) } }
        return watermark?.let { requested ->
            val text = StringBuilder()
            root.expand(requested.text).forEach { fragment ->
                if (fragment.text.length > 4096 - text.length) {
                    DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.RESOLVED_WATERMARK_TEXT_EXCEEDS_4096_CHARACTERS)
                }
                text.append(fragment.text)
            }
            if (text.isBlank()) null else TextWatermark(text.toString(), requested.color, requested.opacity, requested.rotation)
                .also { WatermarkPolicy.validate(it) }
        }
    }

    private fun rejectUnsupportedContent(raw: Any, depth: Int) {
        context.checkActive()
        if (++visits > 1_000_000 || depth > 128) DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.DOCX_STRUCTURE_EXCEEDS_PROCESSING_LIMITS)
        val node = XmlUtils.unwrap(raw)
        val content = children(node)
        if (node is P || node is P.Hyperlink) {
            val text = WordRuns.detectionSource(content)
            if (ContentTemplates.containsContentMarker(text, valuePipes, context)) invalid(RejectionReason.CONTENT_PIPES_ARE_UNSUPPORTED_IN_COMMENTS_FOOTNOTES_OR_ENDNOTES)
        }
        content.forEach { rejectUnsupportedContent(it, depth + 1) }
    }

    private fun process(raw: Any, session: ContentTemplateSession, validateOnly: Boolean, depth: Int, inlineHandled: Boolean = false) {
        val node = XmlUtils.unwrap(raw)
        val paragraph = (node as? P)?.let { paragraphs[it] }
        try {
            if (paragraph != null) DocxFailure.inParagraph(paragraph) { processNode(raw, session, validateOnly, depth, inlineHandled) }
            else processNode(raw, session, validateOnly, depth, inlineHandled)
        } catch (error: GenerationException) {
            if (node is P) fault?.paragraph(node)
            throw error
        }
    }

    private fun indexParagraphs(raw: Any, depth: Int) {
        context.checkActive()
        if (++visits > 1_000_000 || depth > 128) DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.DOCX_STRUCTURE_EXCEEDS_PROCESSING_LIMITS)
        val node = XmlUtils.unwrap(raw)
        if (node is P) paragraphs[node] = ++paragraphIndex
        children(node).forEach { indexParagraphs(it, depth + 1) }
    }

    private fun processNode(raw: Any, session: ContentTemplateSession, validateOnly: Boolean, depth: Int, inlineHandled: Boolean) {
        context.checkActive()
        if (++visits > 1_000_000 || depth > 128) DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.DOCX_STRUCTURE_EXCEEDS_PROCESSING_LIMITS)
        val node = XmlUtils.unwrap(raw)
        val children = children(node)
        if (validateOnly && node is R && WordRuns.plain(node) && !inlineHandled &&
            ContentTemplates.containsContentMarker(WordRuns.source(listOf(node)), valuePipes, context)) {
            invalid(RejectionReason.CONTENT_PIPES_REQUIRE_ORDINARY_PARAGRAPH_OR_HYPERLINK_TEXT_NOT)
        }
        if (node is P || node is P.Hyperlink) WordRuns.apply(children, session, validateOnly, context, valuePipes, fault) { images.drawing(it, owner) }
        if (node is R) WordRuns.mixed(node, session, validateOnly, context, valuePipes, fault)
        if (node is Body || node is Hdr || node is Ftr || node is Tc || node is Tbl || node is CTTxbxContent ||
            node is org.docx4j.vml.CTTextbox || modernTextBox(node)) blocks(children, session, validateOnly, depth)
        else children.toList().forEach { process(it, session, validateOnly, depth + 1,
            (node is P || node is P.Hyperlink) && XmlUtils.unwrap(it) is R) }
        if (!validateOnly && node is Tc && (children.isEmpty() || XmlUtils.unwrap(children.last()) !is P)) children.add(P())
        if (!validateOnly && node is Tbl && children.none { XmlUtils.unwrap(it) is Tr }) invalid(RejectionReason.CONDITIONS_OR_REPEATS_REMOVED_EVERY_TABLE_ROW_WRAP_THE)
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
                DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.DOCX_BLOCK_NESTING_EXCEEDS_THE_CONFIGURED_LIMIT)
            }
            val result = mutableListOf<Block>()
            while (cursor < content.size) {
                context.checkActive()
                val value = content[cursor++]
                val marker = marker(value)
                if (marker in stops) return result to marker
                try {
                    when {
                        marker == null -> result.add(Item(value))
                        marker.startsWith("#if ") -> {
                            val expression = marker.substring(4)
                            validateBlockExpression(value, expression, session)
                            val (yes, stop) = sequence(setOf("#else", "/if"), nesting + 1)
                            if (stop == null) invalid(RejectionReason.UNCLOSED_DOCX_CONDITION)
                            val no = if (stop == "#else") sequence(setOf("/if"), nesting + 1).also {
                                if (it.second == null) invalid(RejectionReason.UNCLOSED_DOCX_CONDITION)
                            }.first else emptyList()
                            result.add(Condition(expression, yes, no))
                        }
                        marker.startsWith("#each ") -> {
                            val match = Regex("(.+)\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*)").matchEntire(marker.substring(6))
                                ?: invalid(RejectionReason.INVALID_DOCX_REPEAT_MARKER)
                            val expression = match.groupValues[1]; val alias = match.groupValues[2]
                            if (alias in setOf("data", "loop", "true", "false", "null")) invalid(RejectionReason.RESERVED_DOCX_REPEAT_ALIAS)
                            validateBlockExpression(value, expression, session)
                            val (body, stop) = sequence(setOf("/each"), nesting + 1)
                            if (stop == null) invalid(RejectionReason.UNCLOSED_DOCX_REPEAT)
                            result.add(Repeat(expression, alias, body))
                        }
                        else -> invalid(RejectionReason.UNEXPECTED_DOCX_BLOCK_MARKER)
                    }
                } catch (error: GenerationException) {
                    throw blockFailure(value, marker, error)
                }
            }
            return result to null
        }
        return sequence(emptySet(), 1).first
    }

    private fun validateBlockExpression(raw: Any, expression: String, session: TemplateSession) {
        val paragraph = markerParagraph(raw)
        val index = paragraph?.let { paragraphs[it] }
        if (index == null) session.validateExpression(expression)
        else DocxFailure.inParagraph(index) { session.validateExpression(expression) }
    }

    private fun blockFailure(raw: Any, marker: String?, error: GenerationException): GenerationException {
        fault?.paragraph(markerParagraph(raw))
        if (context.logging.mode != LoggingMode.DEVELOPMENT || marker == null) return error
        val paragraph = markerParagraph(raw)?.let { paragraphs[it] }
        return GenerationException(error.diagnostics.map { diagnostic ->
            val reason = diagnostic.rejectionReason
            val enriched = if (diagnostic.templateContext == null && diagnostic.code == DiagnosticCodes.INVALID_TEMPLATE && reason != null)
                Diagnostic(diagnostic.code, reason, diagnostic.severity, diagnostic.location ?: "template:0", diagnostic.part)
                    .withTemplateContext(TemplateErrorContext("{$marker}", 0))
                    .withDocumentLocation(diagnostic.documentLocation)
            else diagnostic
            if (enriched.documentLocation == null && paragraph != null) enriched.withDocumentLocation("paragraph:$paragraph") else enriched
        })
    }

    private fun markerParagraph(raw: Any): P? = when (val node = XmlUtils.unwrap(raw)) {
        is P -> node
        is Tr -> node.content.singleOrNull()?.let { XmlUtils.unwrap(it) as? Tc }?.content?.singleOrNull()?.let { XmlUtils.unwrap(it) as? P }
        else -> null
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
        if (marker.length > 4096) DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.DOCX_STRUCTURAL_MARKER_EXCEEDS_THE_LANGUAGE_LIMIT)
        if (paragraph.pPr?.sectPr != null) invalid(RejectionReason.BLOCK_MARKER_PARAGRAPHS_CANNOT_CONTAIN_SECTION_BREAKS)
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

    private fun invalid(reason: RejectionReason): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, reason)
}

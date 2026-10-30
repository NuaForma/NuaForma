package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.template.TemplateSession
import dev.nuaforma.template.ContentTemplateSession
import dev.nuaforma.contracts.ContentResult
import dev.nuaforma.template.ContentTemplates
import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.docx.internal.DocxFailure
import dev.nuaforma.docx.internal.ImageContent
import dev.nuaforma.docx.internal.richtext.RichTextContent
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.template.TemplatePipes
import jakarta.xml.bind.JAXBElement
import org.docx4j.XmlUtils
import org.docx4j.wml.R
import org.docx4j.wml.Text

/** Only adjacent ordinary text runs form a replaceable span; fields and other objects are barriers. */
internal object WordRuns {
    private class Segment(val run: R, val start: Int, val end: Int, val text: String)

    fun plain(value: Any): Boolean = XmlUtils.unwrap(value).let { node ->
        node is R && node.content.isNotEmpty() && node.content.all { it is JAXBElement<*> && it.name.localPart == "t" && it.value is Text }
    }

    fun source(runs: List<Any>): String = runs.joinToString("") { raw ->
        (XmlUtils.unwrap(raw) as R).content.joinToString("") { (XmlUtils.unwrap(it) as Text).value }
    }

    fun detectionSource(content: List<Any>, visible: (R) -> Boolean = { true }): String = content.mapNotNull { XmlUtils.unwrap(it) as? R }.filter(visible)
        .joinToString("") { run -> run.content.mapNotNull { XmlUtils.unwrap(it) as? Text }.joinToString("") { it.value } }

    fun mixed(run: R, session: TemplateSession, validateOnly: Boolean, context: GenerationContext, valuePipes: TemplatePipes, fault: WordTemplateFault? = null) {
        if (plain(run)) return
        if (validateOnly && ContentTemplates.containsContentMarker(run.content.filterIsInstance<JAXBElement<*>>()
                .filter { it.name.localPart != "t" && it.value is Text }.joinToString("") { (it.value as Text).value }, valuePipes, context)) {
            DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.CONTENT_PIPES_CANNOT_BE_INSERTED_INTO_FIELD_INSTRUCTIONS)
        }
        var index = 0
        while (index < run.content.size) {
            fun ordinary(raw: Any) = raw is JAXBElement<*> && raw.name.localPart == "t" && raw.value is Text
            if (!ordinary(run.content[index])) { index++; continue }
            val start = index
            while (index < run.content.size && ordinary(run.content[index])) index++
            val text = run.content.subList(start, index).joinToString("") { (XmlUtils.unwrap(it) as Text).value }
            if ('{' !in text && '}' !in text) continue
            if (validateOnly) {
                try { if (session is ContentTemplateSession) session.validateText(text) else session.validate(text) }
                catch (error: dev.nuaforma.contracts.GenerationException) {
                    fault?.mixed(run, run.content.subList(start, index).toList(), text, error)
                    throw error
                }
                continue
            }
            val replacement = session.expand(text).joinToString("") { it.text }
            (XmlUtils.unwrap(run.content[start]) as Text).apply { value = replacement; space = "preserve" }
            run.content.subList(start + 1, index).clear()
            index = start + 1
        }
    }

    fun apply(content: MutableList<Any>, session: ContentTemplateSession, validateOnly: Boolean, context: GenerationContext, valuePipes: TemplatePipes, visible: (R) -> Boolean,
              fault: WordTemplateFault? = null, richText: (RichTextContent, R, String, Int, Int) -> Any,
              image: (ImageContent) -> org.docx4j.wml.Drawing) {
        if (validateOnly) {
            val instructions = content.joinToString("") { raw -> when (val node = XmlUtils.unwrap(raw)) {
                is R -> if (!visible(node)) "" else node.content.filterIsInstance<JAXBElement<*>>().filter { it.name.localPart != "t" && it.value is Text }
                    .joinToString("") { (it.value as Text).value }
                is org.docx4j.wml.CTSimpleField -> node.instr ?: ""
                else -> ""
            } }
            if (ContentTemplates.containsContentMarker(instructions, valuePipes, context)) {
                DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.CONTENT_PIPES_CANNOT_BE_INSERTED_INTO_FIELD_INSTRUCTIONS)
            }
        }
        var index = 0
        while (index < content.size) {
            context.checkActive()
            if (!plain(content[index]) || !visible(XmlUtils.unwrap(content[index]) as R)) { index++; continue }
            val start = index
            while (index < content.size && plain(content[index]) && visible(XmlUtils.unwrap(content[index]) as R)) index++
            val originals = content.subList(start, index).toList()
            val source = source(originals)
            if ('{' !in source && '}' !in source) continue
            if (validateOnly) {
                try { session.validate(source) }
                catch (error: dev.nuaforma.contracts.GenerationException) { fault?.plain(content, originals, source, error); throw error }
                continue
            }
            var offset = 0
            val segments = originals.flatMap { raw ->
                val run = XmlUtils.unwrap(raw) as R
                run.content.map { text ->
                    val value = (XmlUtils.unwrap(text) as Text).value
                    Segment(run, offset, offset + value.length, value).also { offset += value.length }
                }
            }.filter { it.start < it.end }
            fun segmentIndex(position: Int): Int {
                var left = 0; var right = segments.lastIndex
                while (left < right) {
                    val middle = (left + right + 1) / 2
                    if (segments[middle].start <= position) left = middle else right = middle - 1
                }
                return left
            }
            val replacements = mutableListOf<Any>()
            fun add(segment: Segment, text: String) {
                if (text.isEmpty()) return
                val run = XmlUtils.deepCopy(segment.run)
                run.content.clear()
                val value = Text().apply { this.value = text; space = "preserve" }
                run.content.add(org.docx4j.wml.ObjectFactory().createRT(value))
                replacements.add(run)
            }
            for (fragment in session.expandContent(source)) {
                context.checkActive()
                if (fragment.literal && fragment.text.length == fragment.sourceEnd - fragment.sourceStart) {
                    var position = segmentIndex(fragment.sourceStart)
                    while (position < segments.size && segments[position].start < fragment.sourceEnd) {
                        val segment = segments[position++]
                        val left = maxOf(segment.start, fragment.sourceStart)
                        val right = minOf(segment.end, fragment.sourceEnd)
                        if (left < right) add(segment, segment.text.substring(left - segment.start, right - segment.start))
                    }
                } else {
                    val anchor = segments[segmentIndex(fragment.sourceStart)]
                    val structured = fragment.content
                    if (structured is RichTextContent) {
                        replacements.add(richText(structured, anchor.run, source, fragment.sourceStart, fragment.sourceEnd))
                        continue
                    }
                    if (structured is ImageContent) {
                        val run = XmlUtils.deepCopy(anchor.run)
                        run.content.clear()
                        run.content.add(image(structured))
                        replacements.add(run)
                        continue
                    }
                    val text = when (val result = fragment.content) {
                        null -> fragment.text
                        is ContentResult.Text -> result.text
                        ContentResult.Empty -> ""
                        else -> DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.SELECTED_DOCX_GENERATOR_CANNOT_INSERT_THIS_STRUCTURED_CONTENT_RESULT)
                    }
                    add(anchor, text)
                }
            }
            content.subList(start, index).clear()
            content.addAll(start, replacements)
            index = start + replacements.size
        }
    }
}

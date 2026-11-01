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

/** Passive Word metadata is zero-width; fields, hidden runs and visible objects remain barriers. */
internal object WordRuns {
    private fun previewError(session: TemplateSession, source: String): dev.nuaforma.contracts.GenerationException? = try {
        session.validate(source)
        null
    } catch (error: dev.nuaforma.contracts.GenerationException) {
        if (error.diagnostics.any { it.code == DiagnosticCodes.INVALID_TEMPLATE }) error else throw error
    }

    fun ordinary(raw: Any): Boolean = raw is JAXBElement<*> && raw.name.namespaceURI == W && raw.name.localPart == "t" && raw.value is Text
    private fun wordName(raw: Any): String? {
        if (raw is JAXBElement<*>) return raw.name.localPart.takeIf { raw.name.namespaceURI == W }
        if (raw is R || raw.javaClass.packageName != "org.docx4j.wml") return null
        val root = raw.javaClass.getAnnotation(jakarta.xml.bind.annotation.XmlRootElement::class.java) ?: return null
        return root.name.takeIf { root.namespace == W || root.namespace == "##default" }
    }
    fun passive(raw: Any): Boolean = wordName(raw) in setOf("bookmarkStart", "bookmarkEnd", "proofErr")
    private fun pageHint(raw: Any): Boolean = wordName(raw) == "lastRenderedPageBreak"
    fun spanRun(value: Any): Boolean = (XmlUtils.unwrap(value) as? R)?.let { run ->
        run.content.isNotEmpty() && run.content.all { ordinary(it) || pageHint(it) }
    } == true
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun plain(value: Any): Boolean = XmlUtils.unwrap(value).let { node ->
        node is R && node.content.isNotEmpty() && node.content.all { it is JAXBElement<*> && it.name.localPart == "t" && it.value is Text }
    }

    fun source(runs: List<Any>): String = runs.joinToString("") { raw ->
        (XmlUtils.unwrap(raw) as? R)?.content?.filter { ordinary(it) }?.joinToString("") { (XmlUtils.unwrap(it) as Text).value }.orEmpty()
    }

    fun detectionSource(content: List<Any>, visible: (R) -> Boolean = { true }): String = content.mapNotNull { XmlUtils.unwrap(it) as? R }.filter(visible)
        .joinToString("") { run -> run.content.mapNotNull { XmlUtils.unwrap(it) as? Text }.joinToString("") { it.value } }

    /** Field instructions use the same JAXB Text type but are not printed template instructions. */
    fun printSource(content: List<Any>, visible: (R) -> Boolean): String = content.mapNotNull { XmlUtils.unwrap(it) as? R }.filter(visible)
        .joinToString("") { run -> run.content.filterIsInstance<JAXBElement<*>>().filter { it.name.localPart == "t" }
            .mapNotNull { it.value as? Text }.joinToString("") { it.value } }

    fun mixed(run: R, session: TemplateSession, validateOnly: Boolean, context: GenerationContext, valuePipes: TemplatePipes, fault: WordTemplateFault? = null, preview: Boolean = false) {
        if (spanRun(run)) return
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
            val previewFailure = if (preview) previewError(session, text) else null
            if (previewFailure != null) {
                if (!validateOnly) fault?.mixed(run, run.content.subList(start, index).toList(), text, previewFailure)
                continue
            }
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
              fault: WordTemplateFault? = null, preview: Boolean = false, richText: (RichTextContent, R, String, Int, Int) -> Any,
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
            if (!spanRun(content[index]) || !visible(XmlUtils.unwrap(content[index]) as R)) { index++; continue }
            val start = index
            while (index < content.size && (passive(content[index]) || spanRun(content[index]) && visible(XmlUtils.unwrap(content[index]) as R))) index++
            val originals = content.subList(start, index).toList()
            val span = WordRunSpan(originals, context)
            val source = span.source
            if ('{' !in source && '}' !in source) continue
            val previewFailure = if (preview) previewError(session, source) else null
            if (previewFailure != null) {
                if (!validateOnly) fault?.plain(content, originals, source, previewFailure)
                continue
            }
            if (validateOnly) {
                try { session.validate(source) }
                catch (error: dev.nuaforma.contracts.GenerationException) { fault?.plain(content, originals, source, error); throw error }
                continue
            }
            val replacements = mutableListOf<Any>()
            for (fragment in session.expandContent(source)) {
                context.checkActive()
                if (fragment.literal && fragment.text.length == fragment.sourceEnd - fragment.sourceStart) {
                    span.literal(fragment.sourceStart, fragment.sourceEnd, replacements)
                } else {
                    span.through(fragment.sourceEnd - 1, replacements)
                    val anchor = span.anchor(fragment.sourceStart)
                    val structured = fragment.content
                    if (structured is RichTextContent) {
                        replacements.add(richText(structured, anchor, source, fragment.sourceStart, fragment.sourceEnd))
                        span.through(fragment.sourceEnd, replacements)
                        continue
                    }
                    if (structured is ImageContent) {
                        val run = XmlUtils.deepCopy(anchor)
                        run.content.clear()
                        run.content.add(image(structured))
                        replacements.add(run)
                        span.through(fragment.sourceEnd, replacements)
                        continue
                    }
                    val text = when (val result = fragment.content) {
                        null -> fragment.text
                        is ContentResult.Text -> result.text
                        ContentResult.Empty -> ""
                        else -> DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.SELECTED_DOCX_GENERATOR_CANNOT_INSERT_THIS_STRUCTURED_CONTENT_RESULT)
                    }
                    if (text.isNotEmpty()) replacements.add(span.text(fragment.sourceStart, text))
                    span.through(fragment.sourceEnd, replacements)
                }
            }
            span.through(source.length, replacements)
            span.finish(replacements)
            content.subList(start, index).clear()
            content.addAll(start, replacements)
            index = start + replacements.size
        }
    }
}

package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.*
import jakarta.xml.bind.JAXBElement
import org.docx4j.XmlUtils
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.wml.*

/** References belong only to this private document and are discarded with its request. */
internal class WordTemplateFault(private val context: GenerationContext) {
    private var mark: (() -> Unit)? = null
    private var paragraph: P? = null
    private var table: Tbl? = null
    private var previewPart: String? = null
    private var previewParagraph: Int? = null
    private var previewDiagnostic: Diagnostic? = null

    fun locatePreview(part: String, paragraph: Int?) { previewPart = part; previewParagraph = paragraph }

    private fun capture(diagnostic: Diagnostic) {
        previewDiagnostic = diagnostic.rejectionReason?.let { reason ->
            Diagnostic(diagnostic.code, reason, diagnostic.severity, diagnostic.location, previewPart)
                .withTemplateContext(diagnostic.templateContext).withDocumentLocation(previewParagraph?.let { "paragraph:$it" })
        } ?: diagnostic
    }

    fun finishPreview(document: WordprocessingMLPackage): Boolean {
        val diagnostic = previewDiagnostic ?: return false
        mark?.invoke()
        WordDiagnosticDocument.annotate(document, diagnostic, context, false)
        return true
    }

    fun paragraph(value: P?, ownerTable: Tbl? = null) {
        if (paragraph == null) { paragraph = value; table = ownerTable }
    }

    fun plain(content: MutableList<Any>, originals: List<Any>, source: String, error: GenerationException) {
        val diagnostic = syntax(error) ?: return
        if (mark != null) return
        capture(diagnostic)
        val start = position(diagnostic, source)
        val end = end(source, start)
        mark = {
            val span = WordRunSpan(originals, context)
            val replacement = mutableListOf<Any>()
            span.literal(0, start, replacement)
            span.literal(start, end, replacement, true, "[ERROR 1] ")
            span.literal(end, source.length, replacement)
            span.finish(replacement)
            val index = content.indexOfFirst { it === originals.first() }
            check(index >= 0 && originals.indices.all { content[index + it] === originals[it] })
            content.subList(index, index + originals.size).clear()
            content.addAll(index, replacement)
        }
    }

    fun mixed(run: R, values: List<Any>, source: String, error: GenerationException) {
        val diagnostic = syntax(error) ?: return
        if (mark != null) return
        capture(diagnostic)
        mark = {
            var offset = position(diagnostic, source)
            for (raw in values) {
                context.checkActive()
                val value = XmlUtils.unwrap(raw) as Text
                if (offset <= value.value.length) {
                    value.value = value.value.substring(0, offset) + "[ERROR 1] " + value.value.substring(offset)
                    value.space = "preserve"
                    break
                }
                offset -= value.value.length
            }
            emphasize(run)
        }
    }

    fun annotate(document: WordprocessingMLPackage, diagnostic: Diagnostic, family: String) {
        if (mark == null && paragraph == null) throw GenerationException(listOf(diagnostic))
        mark?.invoke() ?: paragraph!!.let { p ->
            val visibility = WordVisibility(document, context)
            val anchor = p.content.map { XmlUtils.unwrap(it) }.filterIsInstance<R>()
                .firstOrNull { !visibility.hidden(it, p, table) }
            val label = anchor?.let { XmlUtils.deepCopy(it) } ?: R().apply {
                rPr = RPr().apply { rFonts = RFonts().apply { ascii = family; hAnsi = family } }
            }
            label.content.clear()
            label.content.add(text("[ERROR 1] "))
            emphasize(label)
            p.content.add(0, label)
        }
        val body = document.mainDocumentPart.jaxbElement.body
        fun line(value: String, page: Boolean = false) {
            context.checkActive()
            body.content.add(P().apply {
                pPr = PPr().apply {
                    if (page) pageBreakBefore = BooleanDefaultTrue()
                    bidi = BooleanDefaultTrue().apply { isVal = false }
                    jc = Jc().apply { `val` = JcEnumeration.LEFT }
                }
                content.add(R().apply {
                    rPr = RPr().apply {
                        rFonts = RFonts().apply { ascii = family; hAnsi = family }
                        vanish = BooleanDefaultTrue().apply { isVal = false }
                        sz = HpsMeasure().apply { `val` = java.math.BigInteger.valueOf(22) }
                        b = BooleanDefaultTrue().apply { isVal = false }
                        i = BooleanDefaultTrue().apply { isVal = false }
                        color = Color().apply { `val` = "000000" }
                    }
                    content.add(text(value))
                })
            })
        }
        line("TEMPLATE DIAGNOSTIC - ERROR 1", true)
        line("Generation failed. This is a diagnostic copy, not a generated document.")
        line("Template instructions and original content are shown literally. Only the first parser error is marked.")
        line("Reason: ${diagnostic.rejectionReason?.name ?: diagnostic.code.id}")
        line("Part: ${diagnostic.part ?: "unavailable"}; ${diagnostic.documentLocation ?: "paragraph unavailable"}; ${diagnostic.location ?: "position unavailable"}")
        diagnostic.templateContext?.let {
            line("Parser fragment${if (it.excerptStart > 0) " (bounded excerpt)" else ""}:")
            line(it.expression)
            if (!it.redacted) {
                line(" ".repeat(it.position) + "^")
                line("Error character: ${it.excerptStart + it.position} (zero based within the parser fragment).")
            }
        }
        line("A Word object or manual break can interrupt a visually complete expression. Automatic line wrapping does not.")
        diagnostic.rejectionReason?.let { line("Try: ${it.remediation}") }
    }

    private fun syntax(error: GenerationException) = error.diagnostics.firstOrNull {
        it.code == DiagnosticCodes.INVALID_TEMPLATE && it.location?.startsWith("template:") == true
    }
    private fun position(diagnostic: Diagnostic, source: String): Int =
        (diagnostic.location?.removePrefix("template:")?.toIntOrNull() ?: 0).coerceIn(0, (source.length - 1).coerceAtLeast(0))
    private fun end(source: String, start: Int): Int = source.indexOf('}', start).takeIf { it >= start }?.plus(1) ?: source.length
    private fun text(value: String): JAXBElement<Text> = ObjectFactory().createRT(Text().apply { this.value = value; space = "preserve" })
    private fun emphasize(run: R) {
        val properties = run.rPr ?: RPr().also { run.rPr = it }
        properties.color = Color().apply { `val` = "C00000" }
        properties.vanish = BooleanDefaultTrue().apply { isVal = false }
    }
}

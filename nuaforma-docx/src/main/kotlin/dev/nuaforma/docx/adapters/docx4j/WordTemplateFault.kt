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

    fun paragraph(value: P?) { if (paragraph == null) paragraph = value }

    fun plain(content: MutableList<Any>, originals: List<Any>, source: String, error: GenerationException) {
        val diagnostic = syntax(error) ?: return
        if (mark != null) return
        val start = position(diagnostic, source)
        val end = end(source, start)
        mark = {
            var offset = 0
            val replacement = mutableListOf<Any>()
            var labelled = false
            originals.forEach { raw ->
                context.checkActive()
                val original = XmlUtils.unwrap(raw) as R
                val value = WordRuns.source(listOf(raw))
                val cuts = listOf(0, (start - offset).coerceIn(0, value.length), (end - offset).coerceIn(0, value.length), value.length).distinct().sorted()
                cuts.zipWithNext().forEach { (left, right) ->
                    if (right > left) {
                        val selected = offset + left < end && offset + right > start
                        val run = XmlUtils.deepCopy(original)
                        run.content.clear()
                        val prefix = if (selected && !labelled) "[ERROR 1] ".also { labelled = true } else ""
                        run.content.add(text(prefix + value.substring(left, right)))
                        if (selected) emphasize(run)
                        replacement.add(run)
                    }
                }
                offset += value.length
            }
            val index = content.indexOfFirst { it === originals.first() }
            check(index >= 0 && originals.indices.all { content[index + it] === originals[it] })
            content.subList(index, index + originals.size).clear()
            content.addAll(index, replacement)
        }
    }

    fun mixed(run: R, values: List<Any>, source: String, error: GenerationException) {
        val diagnostic = syntax(error) ?: return
        if (mark != null) return
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
            val anchor = p.content.map { XmlUtils.unwrap(it) }.filterIsInstance<R>().firstOrNull()
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
    }
}

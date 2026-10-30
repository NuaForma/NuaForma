package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.adapters.rtf.BoundedRtfReader
import dev.nuaforma.docx.internal.DocxFailure
import dev.nuaforma.docx.internal.richtext.*
import jakarta.xml.bind.JAXBElement
import jakarta.xml.bind.JAXBException
import jakarta.xml.bind.Marshaller
import org.docx4j.XmlUtils
import org.docx4j.wml.*
import java.io.OutputStream
import java.math.BigInteger
import java.util.IdentityHashMap
import javax.xml.namespace.QName

/** Replaces one source paragraph by finalized siblings. Inserted data never re-enters template evaluation. */
internal class WordRichParagraphs(private val paragraph: P, private val context: GenerationContext,
                                  private val visible: (R) -> Boolean, private val splitAllowed: () -> Boolean) {
    private class Insertion(val paragraphs: List<RichParagraph>, val anchor: R, val budget: RichTextBudget)
    private val original = paragraph.content.toList()
    private val propertySizes = IdentityHashMap<RPr, Int>()
    private var qualified = false
    private var nonSpace = 0
    var used = false
        private set

    fun accept(content: RichTextContent, anchor: R, markerSource: String, start: Int, end: Int): Any {
        qualify()
        if (!content.inline && nonSpace != (start until end).count { markerSource[it] != ' ' }) placement()
        val all = BoundedRtfReader(content.source, content.budget).read()
        var first = 0; var last = all.size
        if (content.trimStart) while (first < last && all[first].empty) first++
        if (content.trimEnd) while (last > first && all[last - 1].empty) last--
        val selected = java.util.List.copyOf(all.subList(first, last))
        if (selected.size > 1 && !splitAllowed()) placement()
        used = true
        return Insertion(selected, anchor, content.budget)
    }

    private fun qualify() {
        if (qualified) return
        for (raw in original) {
            context.checkActive()
            val run = XmlUtils.unwrap(raw) as? R ?: placement()
            if (run.content.isNotEmpty() && !WordRuns.plain(raw)) placement()
            if (visible(run)) run.content.forEach { rawText ->
                (XmlUtils.unwrap(rawText) as Text).value.forEach { if (it != ' ') nonSpace++ }
            }
        }
        qualified = true
    }

    private fun segments(): Pair<List<MutableList<Any>>, RichTextBudget> {
        val segments = mutableListOf<MutableList<Any>>()
        var current = mutableListOf<Any>()
        var ownerBudget: RichTextBudget? = null
        var intentionalParagraph = false
        for (raw in paragraph.content) {
            context.checkActive()
            if (raw !is Insertion) { current.add(raw); continue }
            ownerBudget = raw.budget
            if (raw.paragraphs.isNotEmpty()) intentionalParagraph = true
            raw.paragraphs.forEachIndexed { index, rich ->
                if (index > 0) { segments.add(current); current = mutableListOf() }
                rich.spans.forEach { span -> current.add(run(raw.anchor, span, raw.budget)) }
            }
        }
        if (intentionalParagraph || segments.isNotEmpty() || current.isNotEmpty()) segments.add(current)
        return segments to requireNotNull(ownerBudget)
    }

    fun finish(): List<P> {
        check(used)
        val (segments, budget) = segments()
        var paragraphBytes: Int? = null
        if (segments.isEmpty() && !splitAllowed()) {
            // Removing a paragraph must never detach its section, frame, list, heading or page-start ownership.
            paragraph.content.clear()
            return listOf(paragraph)
        }
        if (segments.size > 1) {
            if (!splitAllowed()) placement() // Also covers several individually single-paragraph fragments.
            paragraphBytes = paragraph.pPr?.let { propertySize(it, PPr::class.java, "pPr") } ?: 0
        }
        val result = segments.mapIndexed { index, children ->
            val target = if (index == 0) paragraph else P().apply {
                budget.retain(requireNotNull(paragraphBytes).toLong() + 128)
                pPr = paragraph.pPr?.let { XmlUtils.deepCopy(it) }
            }
            target.content.clear(); target.content.addAll(children)
            target
        }
        if (result.isEmpty()) paragraph.content.clear()
        return result
    }

    private fun run(anchor: R, span: RichSpan, budget: RichTextBudget): R {
        context.checkActive()
        val size = anchor.rPr?.let { properties -> propertySizes.getOrPut(properties) { propertySize(properties, RPr::class.java, "rPr") } } ?: 0
        budget.retain(size.toLong() + 128 + span.text.length * 2L)
        val run = R()
        val properties = anchor.rPr?.let { XmlUtils.deepCopy(it) } ?: RPr()
        run.rPr = properties
        span.style.apply {
            bold?.let { properties.b = toggle(it); properties.bCs = toggle(it) }
            italic?.let { properties.i = toggle(it); properties.iCs = toggle(it) }
            underline?.let { properties.u = U().apply { `val` = if (it) UnderlineEnumeration.SINGLE else UnderlineEnumeration.NONE } }
            strike?.let { properties.strike = toggle(it) }
            family?.let { name -> properties.rFonts = RFonts().apply { ascii = name; hAnsi = name; cs = name; eastAsia = name } }
            halfPoints?.let { size ->
                properties.sz = HpsMeasure().apply { `val` = BigInteger.valueOf(size.toLong()) }
                properties.szCs = HpsMeasure().apply { `val` = BigInteger.valueOf(size.toLong()) }
            }
            color?.let { color -> properties.color = Color().apply {
                `val` = listOf(color.red, color.green, color.blue).joinToString("") { it.toString(16).padStart(2, '0') }
            } }
        }
        // w:tab is structural text; splitting it here avoids illegal literal controls in w:t.
        val factory = ObjectFactory()
        var start = 0
        for (index in 0..span.text.length) {
            if (index < span.text.length && span.text[index] != '\t') continue
            if (index > start) {
                budget.retain(128)
                run.content.add(factory.createRT(Text().apply { value = span.text.substring(start, index); space = "preserve" }))
            }
            if (index < span.text.length) { budget.retain(128); run.content.add(factory.createRTab(R.Tab())) }
            start = index + 1
        }
        return run
    }

    /** Bound style amplification before cloning a caller's arbitrary WML property subtree. */
    private fun <T : Any> propertySize(value: T, type: Class<T>, name: String): Int {
        var count = 0
        var exceeded = false
        val sink = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                context.checkActive()
                if (length > 16_384 - count) { exceeded = true; throw PropertyLimit() }
                count += length
            }
        }
        try {
            org.docx4j.jaxb.Context.jc.createMarshaller().apply { setProperty(Marshaller.JAXB_FRAGMENT, true) }
                .marshal(JAXBElement(QName("http://schemas.openxmlformats.org/wordprocessingml/2006/main", name), type, value), sink)
        } catch (error: JAXBException) {
            context.checkActive()
            if (exceeded) DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.RICH_TEXT_EXCEEDS_PROCESSING_LIMITS)
            DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST, RejectionReason.UNSUPPORTED_RICH_TEXT_PLACEMENT)
        }
        return count
    }
    private class PropertyLimit : java.io.IOException()
    private fun toggle(value: Boolean) = BooleanDefaultTrue().apply { isVal = value }
    private fun placement(): Nothing = DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.UNSUPPORTED_RICH_TEXT_PLACEMENT)
}

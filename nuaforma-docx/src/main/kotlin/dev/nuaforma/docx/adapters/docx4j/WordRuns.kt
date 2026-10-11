package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.template.TemplateSession
import dev.nuaforma.contracts.GenerationContext
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

    fun mixed(run: R, session: TemplateSession, validateOnly: Boolean) {
        if (plain(run)) return
        var index = 0
        while (index < run.content.size) {
            fun ordinary(raw: Any) = raw is JAXBElement<*> && raw.name.localPart == "t" && raw.value is Text
            if (!ordinary(run.content[index])) { index++; continue }
            val start = index
            while (index < run.content.size && ordinary(run.content[index])) index++
            val text = run.content.subList(start, index).joinToString("") { (XmlUtils.unwrap(it) as Text).value }
            if ('{' !in text && '}' !in text) continue
            if (validateOnly) { session.validate(text); continue }
            val replacement = session.expand(text).joinToString("") { it.text }
            (XmlUtils.unwrap(run.content[start]) as Text).apply { value = replacement; space = "preserve" }
            run.content.subList(start + 1, index).clear()
            index = start + 1
        }
    }

    fun apply(content: MutableList<Any>, session: TemplateSession, validateOnly: Boolean, context: GenerationContext) {
        var index = 0
        while (index < content.size) {
            context.checkActive()
            if (!plain(content[index])) { index++; continue }
            val start = index
            while (index < content.size && plain(content[index])) index++
            val originals = content.subList(start, index).toList()
            val source = source(originals)
            if ('{' !in source && '}' !in source) continue
            if (validateOnly) { session.validate(source); continue }
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
            for (fragment in session.expand(source)) {
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
                    add(anchor, fragment.text)
                }
            }
            content.subList(start, index).clear()
            content.addAll(start, replacements)
            index = start + replacements.size
        }
    }
}

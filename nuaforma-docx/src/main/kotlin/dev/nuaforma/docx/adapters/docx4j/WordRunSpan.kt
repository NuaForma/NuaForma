package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.GenerationContext
import org.docx4j.XmlUtils
import org.docx4j.wml.*

/** Visible coordinates exclude passive metadata. Metadata inside a replaced expression collapses to its start. */
internal class WordRunSpan(originals: List<Any>, private val context: GenerationContext) {
    private class Segment(val run: R, val start: Int, val end: Int, val text: String)
    private class Event(val offset: Int, val value: Any)
    private val segments = mutableListOf<Segment>()
    private val events = mutableListOf<Event>()
    private val hintRuns = java.util.IdentityHashMap<R, Boolean>()
    private var eventIndex = 0
    val source: String

    init {
        val text = StringBuilder()
        for (raw in originals) {
            context.checkActive()
            if (WordRuns.passive(raw)) events.add(Event(text.length, raw))
            else {
                val run = XmlUtils.unwrap(raw) as R
                for (value in run.content) {
                    if (WordRuns.ordinary(value)) {
                        val content = (XmlUtils.unwrap(value) as Text).value
                        if (content.isNotEmpty()) segments.add(Segment(run, text.length, text.length + content.length, content))
                        text.append(content)
                    } else {
                        val marker = XmlUtils.deepCopy(run)
                        marker.content.clear()
                        marker.content.add(value)
                        hintRuns[marker] = true
                        events.add(Event(text.length, marker))
                    }
                }
            }
        }
        source = text.toString()
    }

    fun anchor(position: Int): R = segments[segmentIndex(position)].run

    fun through(position: Int, output: MutableList<Any>) {
        while (eventIndex < events.size && events[eventIndex].offset <= position) {
            context.checkActive()
            output.add(events[eventIndex++].value)
        }
    }

    fun literal(start: Int, end: Int, output: MutableList<Any>, emphasize: Boolean = false, prefix: String = "") {
        var pendingPrefix = prefix
        through(start, output)
        if (start >= end) return
        var index = segmentIndex(start)
        while (index < segments.size && segments[index].start < end) {
            context.checkActive()
            val segment = segments[index++]
            var left = maxOf(start, segment.start)
            val right = minOf(end, segment.end)
            while (left < right) {
                through(left, output)
                val next = if (eventIndex < events.size) minOf(right, events[eventIndex].offset) else right
                output.add(run(segment.run, pendingPrefix + segment.text.substring(left - segment.start, next - segment.start), emphasize))
                pendingPrefix = ""
                left = next
            }
        }
        through(end, output)
    }

    /** A cached hint is carried by a surviving run, never turned into an empty printed run. */
    fun finish(output: MutableList<Any>) {
        val pending = mutableListOf<Any>()
        var last: R? = null
        val iterator = output.listIterator()
        while (iterator.hasNext()) {
            context.checkActive()
            val run = XmlUtils.unwrap(iterator.next()) as? R ?: continue
            if (hintRuns.containsKey(run)) {
                pending.addAll(run.content)
                iterator.remove()
            } else {
                if (pending.isNotEmpty()) { run.content.addAll(0, pending); pending.clear() }
                last = run
            }
        }
        // When an expression emits no content, its stale page cache has no remaining run to describe.
        last?.content?.addAll(pending)
    }

    private fun segmentIndex(position: Int): Int {
        var left = 0; var right = segments.lastIndex
        while (left < right) {
            val middle = (left + right + 1) / 2
            if (segments[middle].start <= position) left = middle else right = middle - 1
        }
        return left
    }

    fun text(position: Int, value: String): R = run(anchor(position), value, false)

    private fun run(original: R, value: String, emphasize: Boolean): R = XmlUtils.deepCopy(original).apply {
        content.clear()
        content.add(ObjectFactory().createRT(Text().apply { this.value = value; space = "preserve" }))
        if (emphasize) {
            rPr = (rPr ?: RPr()).apply {
                color = Color().apply { `val` = "C00000" }
                vanish = BooleanDefaultTrue().apply { isVal = false }
            }
        }
    }
}

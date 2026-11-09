package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import javax.xml.namespace.QName

/** Historical columns are validated source metadata; only the current grid determines layout. */
internal object WordGridHistoryMetadata {
    private const val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun normalizeSource(root: XmlElement, context: GenerationContext, reject: (XmlElement, String) -> Nothing): Boolean {
        var changed = false
        val grids = root.descendants().onEach { context.checkActive() }.filter { it.name == QName(word, "tblGrid") }.toList()
        for (grid in grids) {
            val records = grid.elements().filter { it.name.localPart == "tblGridChange" }
            if (records.size > 1) reject(records[1], "Duplicate table-grid history")
            for (record in records) {
                validateRecord(record, reject)
                grid.children.remove(record)
                changed = true
            }
        }
        return changed
    }

    private fun validateRecord(record: XmlElement, reject: (XmlElement, String) -> Nothing) {
        if (record.name != QName(word, "tblGridChange") ||
            record.attributes.keys.any { it.namespaceURI != word || it.localPart !in setOf("id", "author", "date") } ||
            record.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject(record, "Invalid table-grid history metadata")
        val id = record.attribute(word, "id")
        if (id == null || !id.matches(Regex("[0-9]{1,10}")) || id.toLongOrNull() !in 0L..2_147_483_647L)
            reject(record, "Invalid table-grid history identifier")
        if (record.attribute(word, "author")?.length?.let { it > 255 } == true)
            reject(record, "Table-grid history author exceeds the metadata limit")
        record.attribute(word, "date")?.let { date ->
            if (date.length > 64) reject(record, "Invalid table-grid history date")
            try { java.time.format.DateTimeFormatter.ISO_DATE_TIME.parse(date) }
            catch (_: java.time.format.DateTimeParseException) { reject(record, "Invalid table-grid history date") }
        }
        val previous = record.elements().singleOrNull() ?: reject(record, "Table-grid history needs one previous grid")
        if (previous.name != QName(word, "tblGrid") || previous.attributes.isNotEmpty() ||
            previous.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } || previous.elements().size !in 1..16)
            reject(previous, "Invalid previous table grid")
        // Canonicalize retained history for cleanup's final DOCX binding before detaching the print-only copy.
        WordNumericSpellings.normalizeSource(previous, reject)
    }
}

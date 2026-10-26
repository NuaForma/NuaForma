package dev.nuaforma.docx.pdf.ownengine

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Derive a variable-length template from the existing Word-qualified report, without editing references. */
internal object NativeVariableReport {
    fun template(source: ByteArray): ByteArray = ByteArrayOutputStream().also { result ->
        ZipOutputStream(result).use { output -> ZipInputStream(source.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val bytes = input.readBytes()
                output.putNextEntry(ZipEntry(entry.name))
                output.write(if (entry.name == "word/document.xml") transform(bytes.toString(Charsets.UTF_8)).toByteArray() else bytes)
                output.closeEntry()
            }
        } }
    }.toByteArray()

    private fun transform(xml: String): String {
        val start = xml.indexOf("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
        check(start >= 0)
        val end = xml.indexOf("</w:tr>", start) + "</w:tr>".length
        val tableEnd = xml.indexOf("</w:tbl>", end)
        check(end > start && tableEnd >= end)
        val row = xml.substring(start, end)
        val text = Regex("<w:t(?: [^>]*)?>.*?</w:t>")
        check(text.findAll(row).count() == 4)
        fun rowWith(values: List<String>): String {
            var index = 0
            return text.replace(row) { "<w:t xml:space=\"preserve\">${values[index++]}</w:t>" }
        }
        fun marker(value: String) = "<w:tr><w:tc><w:tcPr><w:tcW w:w=\"9638\" w:type=\"dxa\"/><w:gridSpan w:val=\"4\"/></w:tcPr>" +
            "<w:p><w:r><w:t>$value</w:t></w:r></w:p></w:tc></w:tr>"
        val begin = marker("{#each rows as row}")
        val repeated = rowWith(listOf("R{row.id}", "{#if row.active}{row.description}{#else}Niet van toepassing{/if}", "{row.quantity}", "{row.amount}"))
        val close = marker("{/each}")
        return xml.substring(0, start) + begin + repeated + close + xml.substring(tableEnd)
    }

    fun data(count: Int): Map<String, Any?> {
        require(count in 1..120)
        return mapOf("rows" to (1..count).map { index ->
            val id = String.format(Locale.ROOT, "%03d", index)
            mapOf("id" to id, "active" to (index % 3 != 0), "quantity" to index % 4 + 1,
                "description" to ("Variable content $id with retained table formatting. ".repeat(index % 4 + 1)), "amount" to "€ ${100 + index},00")
        })
    }
}

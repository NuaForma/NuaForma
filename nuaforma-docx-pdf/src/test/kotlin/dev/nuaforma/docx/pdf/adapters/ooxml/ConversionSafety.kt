package dev.nuaforma.docx.pdf.adapters.ooxml

import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.docx.pdf.internal.PdfFailure
import java.util.Locale

/** Word fields must not trigger resource retrieval or executable document content. */
internal object ConversionSafety {
    private val forbidden = setOf("DDE", "DDEAUTO", "INCLUDETEXT", "INCLUDEPICTURE", "LINK", "DATABASE", "MACROBUTTON", "EMBED", "CONTROL")

    fun validate(root: XmlElement) {
        val fields = java.util.ArrayDeque<StringBuilder>()
        fun check(instruction: String) {
            val normalized = instruction.trim().uppercase(Locale.ROOT)
            if (normalized.takeWhile { !it.isWhitespace() } in forbidden) unsupported()
            if (normalized.startsWith("HYPERLINK") && listOf("FILE:", "JAVASCRIPT:", "DATA:", "SMB:", "FTP:", "\\\\").any { normalized.contains(it) }) unsupported()
        }
        for (element in root.descendants()) {
            if (element.name.namespaceURI != NativeTextBoxes.WORD) continue
            when (element.name.localPart) {
                "fldSimple" -> check(element.attribute(NativeTextBoxes.WORD, "instr") ?: "")
                "fldChar" -> when (element.attribute(NativeTextBoxes.WORD, "fldCharType")) {
                    "begin" -> {
                        if (fields.size >= 64) unsupported()
                        fields.addLast(StringBuilder())
                    }
                    "separate" -> if (fields.isNotEmpty()) check(fields.last.toString())
                    "end" -> if (fields.isNotEmpty()) check(fields.removeLast().toString())
                }
                "instrText" -> if (fields.isNotEmpty()) {
                    fields.last.append(element.children.filterIsInstance<XmlText>().joinToString("") { it.text })
                    if (fields.last.length > 4096) unsupported()
                }
            }
        }
        fields.forEach { check(it.toString()) }
    }

    private fun unsupported(): Nothing = PdfFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.ACTIVE_OR_RESOURCE_RETRIEVING_WORD_FIELDS_ARE_NOT_SUPPORTED)
}

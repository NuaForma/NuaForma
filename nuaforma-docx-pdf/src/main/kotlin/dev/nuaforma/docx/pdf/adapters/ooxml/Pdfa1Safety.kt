package dev.nuaforma.docx.pdf.adapters.ooxml

import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.docx.pdf.internal.PdfFailure
import java.util.Locale

/** Conservative PDF/A-1b source profile; never ask the renderer to discard transparency. */
internal object Pdfa1Safety {
    fun validate(root: XmlElement) {
        for (element in root.descendants()) {
            val local = element.name.localPart.lowercase(Locale.ROOT)
            if (local.startsWith("alpha") && !(local == "alpha" && element.attribute("", "val") == "100000")) unsupported()
            if (local in effects && element.children.isNotEmpty()) unsupported()
            if (local in setOf("glow", "softedge", "blur", "duotone", "lum", "reflection", "outershdw", "innershdw")) unsupported()
            for ((name, value) in element.attributes) {
                val attribute = name.localPart.lowercase(Locale.ROOT)
                if (attribute.startsWith("opacity") && value.trim() !in setOf("1", "1.0", "100%", "65536f")) unsupported()
                if (attribute == "style" && Regex("(?i)(opacity|filter)\\s*:").containsMatchIn(value)) unsupported()
                if (attribute == "src") unsupported()
            }
        }
    }

    fun imageTargets(relationships: XmlElement, part: String): List<String> {
        val folder = if (part == "_rels/.rels") "" else part.substringBeforeLast("/_rels/", "")
        return relationships.elements().filter { it.attribute("", "Type")?.endsWith("/image") == true }.map { relation ->
            if (relation.attribute("", "TargetMode") !in setOf(null, "Internal")) unsupported()
            val target = runCatching { java.net.URI(relation.attribute("", "Target") ?: "") }.getOrNull() ?: unsupported()
            if (target.scheme != null || target.rawAuthority != null || target.rawQuery != null || target.rawFragment != null) unsupported()
            val path = target.path ?: unsupported()
            if (path.isEmpty() || '\\' in path || ':' in path) unsupported()
            val segments = ArrayDeque<String>()
            val combined = if (path.startsWith('/')) path else "$folder/$path"
            for (segment in combined.split('/')) when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) unsupported() else segments.removeLast()
                else -> segments.addLast(segment)
            }
            if (segments.isEmpty()) unsupported()
            segments.joinToString("/")
        }
    }

    fun media(name: String, bytes: ByteArray) {
        val lower = name.lowercase(Locale.ROOT)
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            if (bytes.size < 3 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() || bytes[2] != 0xff.toByte()) unsupported()
            return
        }
        if (!lower.endsWith(".png") || bytes.size < 33 || !bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))) unsupported()
        var offset = 8
        var header = false
        var end = false
        while (offset <= bytes.size - 12) {
            val length = java.nio.ByteBuffer.wrap(bytes, offset, 4).int
            if (length < 0 || length > bytes.size - offset - 12) unsupported()
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            if (!header && (type != "IHDR" || length != 13)) unsupported()
            if (type == "IHDR") {
                if (header || bytes[offset + 17].toInt() !in setOf(0, 2, 3)) unsupported()
                header = true
            }
            if (type == "tRNS") unsupported()
            if (type == "IEND") {
                if (length != 0 || offset + 12 != bytes.size) unsupported()
                end = true
                break
            }
            offset += length + 12
        }
        if (!header || !end) unsupported()
    }

    private val effects = setOf("effectlst", "effectdag", "clrchange", "clrrepl")
    private fun unsupported(): Nothing = PdfFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT,
        "PDF/A-1b supports a restricted transparency-free template profile with opaque PNG/JPEG images and no drawing effects; choose PDF/A-2b or PDF/A-3b")
}

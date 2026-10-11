package dev.nuaforma.docx.internal

import dev.nuaforma.contracts.*

internal class FontPolicy(val mode: String, val fonts: List<FontResource>) {
    class FontResource(val family: String, val face: String, val resource: String)

    companion object {
        fun read(options: Map<String, Any?>): FontPolicy {
            val mode = options["fontMode"] ?: "preserve"
            if (mode !in listOf("preserve", "validate", "embed")) invalid("fontMode must be preserve, validate or embed")
            val raw = options["fonts"] ?: emptyList<Any?>()
            if (raw !is List<*> || raw.size > 64) invalid("fonts must be an array with at most 64 entries")
            val fonts = raw.map { value ->
                if (value !is Map<*, *> || value.keys.any { it !in setOf("family", "face", "resource") }) invalid("Invalid font resource declaration")
                val family = value["family"] as? String ?: invalid("A font needs a family")
                val resource = value["resource"] as? String ?: invalid("A font needs a logical resource identifier")
                val face = value["face"] ?: "regular"
                if (family.isBlank() || family.length > 128 || family.any { it.isISOControl() } ||
                    !resource.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) || face !in listOf("regular", "bold", "italic", "boldItalic")) invalid("Invalid font family, face or resource identifier")
                FontResource(family, face as String, resource)
            }
            if (fonts.map { it.family to it.face }.distinct().size != fonts.size) invalid("Font family/face declarations must be unique")
            if (mode == "preserve" && fonts.isNotEmpty()) invalid("Supplied fonts require validate or embed mode")
            return FontPolicy(mode as String, fonts)
        }

        private fun invalid(message: String): Nothing = DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_OPTION, message)
    }
}

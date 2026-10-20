package dev.nuaforma.docx.internal

import dev.nuaforma.contracts.*

internal class FontPolicy(val mode: String, val fonts: List<FontResource>) {
    class FontResource(val family: String, val face: String, val resource: String)

    companion object {
        fun read(options: Map<String, Any?>): FontPolicy {
            val mode = options["fontMode"] ?: "preserve"
            if (mode !in listOf("preserve", "validate", "embed")) invalid(RejectionReason.FONTMODE_MUST_BE_PRESERVE_VALIDATE_OR_EMBED)
            val raw = options["fonts"] ?: emptyList<Any?>()
            if (raw !is List<*> || raw.size > 64) invalid(RejectionReason.FONTS_MUST_BE_AN_ARRAY_WITH_AT_MOST_64)
            val fonts = raw.map { value ->
                if (value !is Map<*, *> || value.keys.any { it !in setOf("family", "face", "resource") }) invalid(RejectionReason.INVALID_FONT_RESOURCE_DECLARATION)
                val family = value["family"] as? String ?: invalid(RejectionReason.A_FONT_NEEDS_A_FAMILY)
                val resource = value["resource"] as? String ?: invalid(RejectionReason.A_FONT_NEEDS_A_LOGICAL_RESOURCE_IDENTIFIER)
                val face = value["face"] ?: "regular"
                if (family.isBlank() || family.length > 128 || family.any { it.isISOControl() } ||
                    !resource.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) || face !in listOf("regular", "bold", "italic", "boldItalic")) invalid(RejectionReason.INVALID_FONT_FAMILY_FACE_OR_RESOURCE_IDENTIFIER)
                FontResource(family, face as String, resource)
            }
            if (fonts.map { it.family to it.face }.distinct().size != fonts.size) invalid(RejectionReason.FONT_FAMILY_FACE_DECLARATIONS_MUST_BE_UNIQUE)
            if (mode == "preserve" && fonts.isNotEmpty()) invalid(RejectionReason.SUPPLIED_FONTS_REQUIRE_VALIDATE_OR_EMBED_MODE)
            return FontPolicy(mode as String, fonts)
        }

        private fun invalid(reason: RejectionReason): Nothing = DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_OPTION, reason)
    }
}

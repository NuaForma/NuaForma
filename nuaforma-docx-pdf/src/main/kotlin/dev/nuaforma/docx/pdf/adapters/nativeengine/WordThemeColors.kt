package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import dev.nuaforma.pdf.PdfColor
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Resolve qualified run colors and cell fills; palette state belongs to one source read. */
internal class WordThemeColors(
    private val theme: XmlElement?,
    private val reject: (XmlElement, String) -> Nothing,
) {
    private val resolved = mutableMapOf<String, PdfColor>()

    fun resolve(node: XmlElement): PdfColor {
        validateShape(node, reject)
        val role = node.attribute(W, "themeColor")
        if (role == null || role == "none") return rgb(node, node.attribute(W, "val"))
        return themed(node, role, node.attribute(W, "themeTint"), node.attribute(W, "themeShade"))
    }

    fun cellFill(node: XmlElement): PdfColor? {
        validateCellFill(node, reject)
        val role = node.attribute(W, "themeFill")
        if (role == "none") return null
        if (role == null) return node.attribute(W, "fill")?.takeUnless { it == "auto" }?.let { rgb(node, it) }
        return themed(node, role, node.attribute(W, "themeFillTint"), node.attribute(W, "themeFillShade"))
    }

    private fun themed(node: XmlElement, role: String, tintValue: String?, shadeValue: String?): PdfColor {
        val original = resolved.getOrPut(role) { palette(node, role) }
        val tint = tintValue?.toInt(16)
        val shade = shadeValue?.toInt(16)
        return if (tint != null) transform(original, tint, true)
            else if (shade != null) transform(original, shade, false) else original
    }

    private fun palette(source: XmlElement, role: String): PdfColor {
        val root = theme ?: reject(source, "Theme color requires an embedded document theme")
        val elements = child(root, "themeElements")
        val scheme = child(elements, "clrScheme")
        val slot = child(scheme, roles.getValue(role))
        val values = slot.elements()
        if (slot.attributes.isNotEmpty() || values.size != 1 || slot.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(slot, "Invalid theme color slot")
        val value = values.single()
        if (value.name.namespaceURI != A || value.elements().isNotEmpty() || value.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            reject(value, "Unsupported theme color transform")
        return when (value.name.localPart) {
            "srgbClr" -> {
                if (value.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart != "val" }) reject(value, "Invalid RGB theme color")
                rgb(value, value.attribute("", "val"))
            }
            "sysClr" -> {
                val expected = when (value.attribute("", "val")) { "windowText" -> "000000"; "window" -> "FFFFFF"; else -> null }
                if (expected == null || value.attribute("", "lastClr")?.uppercase() != expected ||
                    value.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart !in setOf("val", "lastClr") })
                    reject(value, "Only standard black and white system theme colors are qualified")
                rgb(value, expected)
            }
            else -> reject(value, "Unsupported theme palette color")
        }
    }

    private fun child(parent: XmlElement, name: String): XmlElement =
        parent.elements().singleOrNull { it.name.namespaceURI == A && it.name.localPart == name }
            ?: reject(parent, "Missing or duplicate theme color property: $name")

    private fun rgb(node: XmlElement, value: String?): PdfColor {
        if (value?.matches(Regex("[0-9A-Fa-f]{6}")) != true) reject(node, "Explicit RGB color required")
        return PdfColor(value.substring(0, 2).toInt(16), value.substring(2, 4).toInt(16), value.substring(4, 6).toInt(16))
    }

    private fun transform(color: PdfColor, amount: Int, tint: Boolean): PdfColor {
        if (amount == 255) return color
        if (amount == 0) return if (tint) PdfColor(255, 255, 255) else PdfColor(0, 0, 0)
        val red = color.red / 255.0
        val green = color.green / 255.0
        val blue = color.blue / 255.0
        val high = max(red, max(green, blue))
        val low = min(red, min(green, blue))
        val luminance = (high + low) / 2
        val adjusted = if (tint) 1 - (1 - luminance) * amount / 255 else luminance * amount / 255
        val difference = high - low
        val saturation = if (difference == 0.0) 0.0 else difference / (1 - abs(2 * luminance - 1))
        val hue = if (difference == 0.0) 0.0 else when (high) {
            red -> ((green - blue) / difference + if (green < blue) 6 else 0) / 6
            green -> ((blue - red) / difference + 2) / 6
            else -> ((red - green) / difference + 4) / 6
        }
        val chroma = (1 - abs(2 * adjusted - 1)) * saturation
        val intermediate = chroma * (1 - abs((hue * 6) % 2 - 1))
        val channels = when (floor(hue * 6).toInt()) {
            0 -> doubleArrayOf(chroma, intermediate, 0.0)
            1 -> doubleArrayOf(intermediate, chroma, 0.0)
            2 -> doubleArrayOf(0.0, chroma, intermediate)
            3 -> doubleArrayOf(0.0, intermediate, chroma)
            4 -> doubleArrayOf(intermediate, 0.0, chroma)
            else -> doubleArrayOf(chroma, 0.0, intermediate)
        }
        val offset = adjusted - chroma / 2
        val rgb = channels.map { floor((it + offset) * 255 + 1e-9).toInt().coerceIn(0, 255) }
        return PdfColor(rgb[0], rgb[1], rgb[2])
    }

    companion object {
        private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
        private val roles = mapOf("dark1" to "dk1", "light1" to "lt1", "dark2" to "dk2", "light2" to "lt2",
            "text1" to "dk1", "background1" to "lt1", "text2" to "dk2", "background2" to "lt2",
            "hyperlink" to "hlink", "followedHyperlink" to "folHlink") + (1..6).associate { "accent$it" to "accent$it" }

        fun validateSource(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
            if (root.name.namespaceURI == A && root.name.localPart == "theme") validateThemeSource(root, reject)
            for (properties in root.descendants().filter { it.name.namespaceURI == W && it.name.localPart == "tcPr" }) {
                val fills = properties.elements().filter { it.name.localPart == "shd" }
                if (fills.none { node -> node.attributes.keys.any { it.localPart in setOf("themeFill", "themeFillTint", "themeFillShade") } }) continue
                if (fills.size > 1) reject(fills[1], "Duplicate cell theme fill")
                validateCellFill(fills.single(), reject)
            }
            for (properties in root.descendants().filter { it.name.namespaceURI == W && it.name.localPart == "rPr" }) {
                val colors = properties.elements().filter { it.name.namespaceURI == W && it.name.localPart == "color" }
                if (colors.none { node -> node.attributes.keys.any { it.namespaceURI == W && it.localPart in setOf("themeColor", "themeTint", "themeShade") } }) continue
                if (colors.size != 1) reject(colors[1], "Duplicate run theme color")
                validateShape(colors.single(), reject)
            }
        }

        private fun validateThemeSource(root: XmlElement, reject: (XmlElement, String) -> Nothing) {
            val elements = root.elements().filter { it.name.namespaceURI == A && it.name.localPart == "themeElements" }
            if (elements.size > 1) reject(elements[1], "Duplicate original theme elements")
            val schemes = elements.singleOrNull()?.elements()?.filter { it.name.namespaceURI == A && it.name.localPart == "clrScheme" }.orEmpty()
            if (schemes.size > 1) reject(schemes[1], "Duplicate original theme color scheme")
            val scheme = schemes.singleOrNull() ?: return
            if (scheme.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart != "name" } ||
                scheme.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() }) reject(scheme, "Invalid original theme color scheme")
            val slots = scheme.elements()
            if (slots.size != 12 || slots.any { it.name.namespaceURI != A || it.name.localPart !in roles.values })
                reject(scheme, "Original theme color scheme requires twelve qualified slots")
            for (name in roles.values.toSet()) {
                val slot = slots.singleOrNull { it.name.localPart == name } ?: reject(scheme, "Missing or duplicate original theme color slot")
                if (slot.attributes.isNotEmpty() || slot.elements().size != 1 || slot.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
                    reject(slot, "Invalid original theme color slot")
                validatePaletteSource(slot.elements().single(), reject)
            }
        }

        private fun validatePaletteSource(node: XmlElement, reject: (XmlElement, String) -> Nothing) {
            val rgb = node.name.namespaceURI == A && node.name.localPart == "srgbClr"
            val system = node.name.namespaceURI == A && node.name.localPart == "sysClr"
            val attributes = if (rgb) setOf("val") else setOf("val", "lastClr")
            if ((!rgb && !system) || node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() } ||
                node.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart !in attributes })
                reject(node, "Unsupported original theme palette color")
            if (rgb && node.attribute("", "val")?.matches(Regex("[0-9A-Fa-f]{6}")) != true)
                reject(node, "Invalid original RGB theme color")
            if (system) {
                val expected = when (node.attribute("", "val")) { "windowText" -> "000000"; "window" -> "FFFFFF"; else -> null }
                if (expected == null || node.attribute("", "lastClr")?.uppercase() != expected)
                    reject(node, "Only standard black and white system theme colors are qualified")
            }
        }

        private fun validateCellFill(node: XmlElement, reject: (XmlElement, String) -> Nothing) {
            if (node.name.namespaceURI != W || node.attributes.keys.any { it.namespaceURI != W ||
                    it.localPart !in setOf("val", "color", "fill", "themeFill", "themeFillTint", "themeFillShade") } ||
                node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
                reject(node, "Invalid cell theme fill structure")
            if (node.attribute(W, "val") != "clear" || node.attribute(W, "color") != "auto")
                reject(node, "Unsupported cell shading")
            val role = node.attribute(W, "themeFill")
            if (role != null && role != "none" && role !in roles) reject(node, "Unsupported cell theme fill role")
            val cached = node.attribute(W, "fill")
            if (cached != null && cached != "auto" && !cached.matches(Regex("[0-9A-Fa-f]{6}"))) reject(node, "Invalid cached cell fill")
            for (name in listOf("themeFillTint", "themeFillShade")) node.attribute(W, name)?.let { value ->
                if (role == null || role == "none" || !value.matches(Regex("[0-9A-Fa-f]{2}"))) reject(node, "Invalid cell theme fill transform")
            }
        }

        private fun validateShape(node: XmlElement, reject: (XmlElement, String) -> Nothing) {
            if (node.attributes.keys.any { it.namespaceURI != W || it.localPart !in setOf("val", "themeColor", "themeTint", "themeShade") } ||
                node.elements().isNotEmpty() || node.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
                reject(node, "Invalid run theme color structure")
            val role = node.attribute(W, "themeColor")
            if (role != null && role != "none" && role !in roles) reject(node, "Unsupported run theme color role")
            val cached = node.attribute(W, "val")
            if (cached != null && cached != "auto" && !cached.matches(Regex("[0-9A-Fa-f]{6}"))) reject(node, "Invalid cached run color")
            for (name in listOf("themeTint", "themeShade")) node.attribute(W, name)?.let { value ->
                if (role == null || role == "none" || !value.matches(Regex("[0-9A-Fa-f]{2}"))) reject(node, "Invalid run theme color transform")
            }
        }
    }
}

package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.nativeengine.emf.EmfPng
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/** The qualified SVG profile paints static filled paths from a bounded embedded XML part. */
internal object WordSvgPicture {
    private const val svg = "http://www.w3.org/2000/svg"
    private const val xml = "http://www.w3.org/XML/1998/namespace"
    private const val maxSize = 1024
    private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val token = Regex("[MCLZ]|"+number.pattern)
    private val delimiters = Regex("[\\t\\r\\n ]*(?:,[\\t\\r\\n ]*)?")
    private val color = Regex("#(?:[0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})")

    fun rasterize(bytes: ByteArray, context: GenerationContext, reject: (String) -> Nothing): ByteArray {
        if (bytes.size > minOf(131_072L, context.limits.maxExpandedBytes)) reject("SVG picture byte budget exceeded")
        val root = ConversionXml(context).read(bytes)
        if (root.name.namespaceURI != svg || root.name.localPart != "svg" ||
            root.attributes.any { (name, value) ->
                when (name.namespaceURI) {
                    xml -> name.localPart != "space" || value !in setOf("default", "preserve")
                    "" -> when (name.localPart) {
                        "id" -> value.length !in 1..64 || value.any(Char::isISOControl)
                        "viewBox" -> false
                        "overflow" -> value !in setOf("hidden", "visible")
                        else -> true
                    }
                    else -> true
                }
            }) reject("Unsupported SVG viewport properties")
        val view = root.attribute("", "viewBox")?.trim()?.split(Regex("[\\t\\r\\n ,]+"))
            ?.map { coordinate(it, reject) } ?: reject("Missing SVG view box")
        if (view.size != 4 || view[2] < .01 || view[3] < .01) reject("Invalid SVG view box")
        val (x, y, width, height) = view
        val scale = maxSize / maxOf(width, height)
        val pixelsX = (width * scale).roundToInt()
        val pixelsY = (height * scale).roundToInt()
        if (pixelsX !in 8..maxSize || pixelsY !in 8..maxSize ||
            pixelsX.toLong() * pixelsY * 4 > context.limits.maxExpandedBytes) reject("SVG picture dimensions exceed the qualified profile")
        val image = BufferedImage(pixelsX, pixelsY, BufferedImage.TYPE_INT_ARGB)
        val canvas = image.createGraphics()
        var elements = 0
        var segments = 0
        var painted = false
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            canvas.scale(pixelsX.toDouble() / width, pixelsY.toDouble() / height)
            canvas.translate(-x, -y)
            fun visit(group: XmlElement, depth: Int) {
                if (depth > 8 || group.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
                    reject("Unsupported nested SVG picture structure")
                for (element in group.elements()) {
                    context.checkActive()
                    if (++elements > 256 || element.name.namespaceURI != svg) reject("Unsupported SVG picture element")
                    when (element.name.localPart) {
                        "g" -> {
                            if (element.attributes.isNotEmpty()) reject("SVG group styling is outside the qualified profile")
                            visit(element, depth + 1)
                        }
                        "path" -> {
                            if (element.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart !in setOf("fill", "d") } ||
                                element.elements().isNotEmpty() || element.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
                                reject("Unsupported SVG path properties")
                            val fill = element.attribute("", "fill") ?: "#000000"
                            if (fill != "none" && !fill.matches(color)) reject("Unsupported SVG path fill")
                            val path = path(element.attribute("", "d") ?: reject("Missing SVG path"), context, reject) {
                                if (++segments > 4096) reject("SVG picture path budget exceeded")
                            }
                            if (fill != "none") {
                                val hex = fill.substring(1).let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
                                canvas.color = Color(hex.toInt(16))
                                canvas.fill(path)
                                painted = true
                            }
                        }
                        else -> reject("Unsupported SVG picture element")
                    }
                }
            }
            visit(root, 0)
        } finally {
            canvas.dispose()
        }
        if (!painted) reject("SVG picture contains no supported fill")
        return EmfPng.encode(image, context)
    }

    private fun coordinate(raw: String, reject: (String) -> Nothing): Double =
        raw.takeIf { it.length <= 24 && it.matches(number) }?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in -1_000_000.0..1_000_000.0 } ?: reject("Invalid SVG coordinate")

    private fun path(data: String, context: GenerationContext, reject: (String) -> Nothing, segment: () -> Unit): Path2D.Double {
        if (data.length !in 1..32_768) reject("SVG path size is outside the qualified budget")
        val entries = token.findAll(data).toList()
        val path = Path2D.Double(Path2D.WIND_NON_ZERO)
        var command = ' '
        var moved = false
        var offset = 0
        var previousWasCoordinate = false
        var i = 0

        fun entry(): String {
            if (i >= entries.size) reject("Incomplete SVG path command")
            val item = entries[i++]
            val gap = data.substring(offset, item.range.first)
            val isCoordinate = item.value.singleOrNull()?.let { it !in "MCLZ" } ?: true
            if (!gap.matches(delimiters) || ',' in gap && (!previousWasCoordinate || !isCoordinate))
                reject("Malformed SVG path separators")
            previousWasCoordinate = isCoordinate
            offset = item.range.last + 1
            return item.value
        }
        fun point(): Double = coordinate(entry(), reject)

        while (i < entries.size) {
            context.checkActive()
            val mark = entries[i].value.singleOrNull()
            if (mark != null && mark in "MCLZ") {
                command = entry().single()
            } else if (command == ' ') reject("Missing SVG path command")
            segment()
            if (!moved && command != 'M') reject("SVG path requires a starting point")
            when (command) {
                'M' -> { path.moveTo(point(), point()); command = 'L'; moved = true }
                'L' -> path.lineTo(point(), point())
                'C' -> path.curveTo(point(), point(), point(), point(), point(), point())
                'Z' -> { path.closePath(); command = ' ' }
            }
        }
        if (!moved || data.substring(offset).any { it !in " \t\r\n" }) reject("Incomplete SVG path data")
        return path
    }
}

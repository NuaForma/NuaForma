package dev.nuaforma.docx.pdf.adapters.nativeengine.emf

import dev.nuaforma.contracts.*
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.*
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.abs

/** Embedded pixels and explicitly supplied physical fonts; no platform font lookup or GDI escape. */
internal object EmfContent {

    fun font(r: EmfRecord): EmfFont {
        r.require(r.int(16) == 0 && r.int(20) == r.int(24), "Font width/orientation is not qualified")
        val height = r.int(12); val weight = r.int(28)
        r.require(height != 0 && abs(height.toLong()) <= 16_384 && weight in 0..1000 && abs(r.int(20).toLong()) <= 36_000, "Invalid font dimensions")
        val nameBytes = r.span(40, 64)
        var length = 0
        while (length < nameBytes.size && (nameBytes[length] != 0.toByte() || nameBytes[length + 1] != 0.toByte())) length += 2
        val name = decode(nameBytes.copyOf(length), "UTF-16LE")
        r.require(name.isNotBlank() && name.length <= 32 && name.none { it.isISOControl() }, "Invalid font name")
        r.require(r.bytes[32].toInt() in 0..1 && r.bytes[33].toInt() in 0..1 && r.bytes[34].toInt() in 0..1, "Invalid font decorations")
        return EmfFont(name, height, weight >= 600, r.bytes[32] != 0.toByte(), r.bytes[33] != 0.toByte(),
            r.bytes[34] != 0.toByte(), r.int(20) / 10.0, r.bytes[35].toInt() and 255)
    }

    fun bitmap(r: EmfRecord, canvas: BufferedImage?, state: EmfState, base: AffineTransform, context: GenerationContext) {
        r.require(r.int(64) == 0 && r.int(68) == 0x00cc0020, "Only RGB source-copy bitmaps are qualified")
        r.require(r.int(48) >= 80 && r.int(56) >= 80 &&
            (r.int(48).toLong() + r.int(52) <= r.int(56) || r.int(56).toLong() + r.int(60) <= r.int(48)), "Overlapping bitmap payloads")
        val info = r.span(r.int(48), r.int(52))
        val b = ByteBuffer.wrap(info).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        r.require(info.size >= 40 && b.getInt(0) == 40 && b.getShort(12).toInt() == 1 && b.getInt(16) == 0, "Unsupported bitmap header/compression")
        val width = b.getInt(4); val signedHeight = b.getInt(8); val bits = b.getShort(14).toInt()
        r.require(width > 0 && signedHeight != 0 && bits in setOf(24, 32), "Unsupported bitmap dimensions/depth")
        budget(width <= 4096 && abs(signedHeight.toLong()) <= 4096)
        val height = abs(signedHeight)
        budget(width.toLong() * height <= 4_194_304)
        val stride = ((width * bits + 31) / 32) * 4
        val data = r.span(r.int(56), r.int(60))
        r.require(stride.toLong() * height <= data.size, "Truncated bitmap pixels")
        val sx = r.int(32); val sy = r.int(36); val sw = r.int(40); val sh = r.int(44)
        r.require(sx >= 0 && sy >= 0 && sw > 0 && sh > 0 && sx.toLong() + sw <= width && sy.toLong() + sh <= height, "Invalid bitmap source rectangle")
        r.point(24)
        r.require(r.int(72) in 1..1_000_000 && r.int(76) in 1..1_000_000 &&
            abs(r.int(24).toLong() + r.int(72)) <= 1_000_000 && abs(r.int(28).toLong() + r.int(76)) <= 1_000_000,
            "Mirrored or unbounded bitmaps are not qualified")
        if (canvas == null) return
        val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        try {
            for (y in 0 until height) {
                context.checkActive()
                val row = if (signedHeight > 0) height - 1 - y else y
                for (x in 0 until width) {
                    val at = row * stride + x * (bits / 8)
                    bitmap.setRGB(x, y, (data[at].toInt() and 255) or ((data[at + 1].toInt() and 255) shl 8) or ((data[at + 2].toInt() and 255) shl 16))
                }
            }
            val g = graphics(canvas, state, base)
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, if (state.stretchMode == 4) RenderingHints.VALUE_INTERPOLATION_BICUBIC else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                g.drawImage(bitmap, r.int(24), r.int(28), r.int(24) + r.int(72), r.int(28) + r.int(76), sx, sy, sx + sw, sy + sh, null)
            } finally { g.dispose() }
        } finally { bitmap.flush() }
    }

    fun text(r: EmfRecord, canvas: BufferedImage?, state: EmfState, base: AffineTransform,
             lookup: ((String, Boolean, Boolean) -> Font)?, context: GenerationContext) {
        r.require(r.int(24) in 1..2, "Invalid text graphics mode")
        val count = r.int(44); val options = r.int(52)
        r.require(count >= 0 && options and (2 or 4 or 16 or 8192).inv() == 0, "Unsupported text flags/count")
        budget(count <= 8192)
        val glyphIndices = options and 16 != 0
        r.require(!glyphIndices || r.type == 84, "ANSI glyph indices are unsupported")
        val wide = r.type == 84
        r.require(count == 0 || r.int(48) >= 76, "Text payload overlaps its header")
        val bytes = r.span(r.int(48), count * if (wide) 2 else 1)
        val encoding = if (wide) "UTF-16LE" else when (state.font?.charset) {
            0, 1 -> "windows-1252"; 204 -> "windows-1251"; 177 -> "windows-1255"; 178 -> "windows-1256"; 238 -> "windows-1250"
            else -> throw EmfFailure("Unsupported ANSI character set")
        }
        val value = if (glyphIndices) null else decode(bytes, encoding)
        r.require(value == null || value.none { it.isISOControl() }, "Control characters in image text")
        val perCharacter = if (options and 8192 != 0) 2 else 1
        r.require(r.int(72) == 0 || r.int(72) >= 76 &&
            (r.int(48).toLong() + bytes.size <= r.int(72) || r.int(72).toLong() + count.toLong() * perCharacter * 4 <= r.int(48)), "Overlapping text payloads")
        val advances = if (r.int(72) == 0) null else List(count * perCharacter) { r.int(r.int(72) + it * 4).also { v -> r.require(abs(v.toLong()) <= 1_000_000, "Unbounded text advance") } }
        val selected = state.font
        r.require(count == 0 || selected != null, "Image text needs an explicit font")
        r.point(36)
        if (options and 6 != 0) {
            val a = r.point(56); val b = r.point(64)
            r.require(b.x >= a.x && b.y >= a.y, "Invalid text rectangle")
        }
        if (canvas == null) return
        val g = graphics(canvas, state, base)
        try {
            val rect = Rectangle2D.Double(r.int(56).toDouble(), r.int(60).toDouble(), r.int(64).toDouble() - r.int(56), r.int(68).toDouble() - r.int(60))
            if (options and 2 != 0) { g.color = state.background; g.fill(rect) }
            if (options and 4 != 0) g.clip(rect)
            if (count > 0) drawText(r, g, state, requireNotNull(selected), value, bytes, advances, perCharacter, lookup, context)
        } finally { g.dispose() }
    }

    private fun drawText(r: EmfRecord, g: Graphics2D, state: EmfState, selected: EmfFont, value: String?, bytes: ByteArray,
                         advances: List<Int>?, step: Int, lookup: ((String, Boolean, Boolean) -> Font)?, context: GenerationContext) {
        val physical = lookup?.invoke(selected.family, selected.bold, selected.italic) ?: throw EmfFailure("The image font is not supplied")
        val frc = FontRenderContext(AffineTransform(), true, true)
        val unit = physical.deriveFont(1f)
        val unitMetrics = unit.getLineMetrics(" ", frc)
        val size = if (selected.height < 0) -selected.height.toFloat() else selected.height / (unitMetrics.ascent + unitMetrics.descent)
        val font = physical.deriveFont(size)
        r.require(value == null || font.canDisplayUpTo(value) == -1, "Supplied image font lacks a glyph")
        val glyphs = if (value == null) {
            val data = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val indices = IntArray(bytes.size / 2) { (data.short.toInt() and 65535).also { id -> r.require(id in 0 until font.numGlyphs, "Invalid glyph index") } }
            font.createGlyphVector(frc, indices)
        } else font.layoutGlyphVector(frc, value.toCharArray(), 0, value.length, Font.LAYOUT_LEFT_TO_RIGHT)
        val positions = mutableListOf(Point2D.Double())
        var x = 0.0; var y = 0.0
        if (advances != null) {
            for (i in advances.indices step step) { positions += Point2D.Double(x, y); x += advances[i]; if (step == 2) y += advances[i + 1] }
            for (i in 0 until glyphs.numGlyphs) glyphs.setGlyphPosition(i, positions[glyphs.getGlyphCharIndex(i)])
            glyphs.setGlyphPosition(glyphs.numGlyphs, Point2D.Double(x, y))
        } else x = glyphs.getGlyphPosition(glyphs.numGlyphs).x
        val metrics = font.getLineMetrics(value ?: " ", frc)
        val reference = if (state.textAlign and 1 != 0) state.point else r.point(36)
        g.translate(reference.x, reference.y); g.rotate(-Math.toRadians(selected.angle))
        val horizontal = when (state.textAlign and 6) { 0 -> 0.0; 2 -> -x; 6 -> -x / 2; else -> throw EmfFailure("Invalid horizontal text alignment") }
        val vertical = when (state.textAlign and 24) { 0 -> metrics.ascent.toDouble(); 8 -> -metrics.descent.toDouble(); 24 -> 0.0; else -> throw EmfFailure("Invalid vertical text alignment") }
        g.translate(horizontal, vertical); g.color = state.textColor
        if (state.backgroundMode == 2) { g.color = state.background; g.fill(Rectangle2D.Double(0.0, -metrics.ascent.toDouble(), x, (metrics.ascent + metrics.descent).toDouble())); g.color = state.textColor }
        for (i in 0 until glyphs.numGlyphs) { context.checkActive(); g.fill(glyphs.getGlyphOutline(i)) }
        if (selected.underline) g.fill(Rectangle2D.Double(0.0, metrics.underlineOffset.toDouble(), x, metrics.underlineThickness.toDouble()))
        if (selected.strike) g.fill(Rectangle2D.Double(0.0, metrics.strikethroughOffset.toDouble(), x, metrics.strikethroughThickness.toDouble()))
        if (state.textAlign and 1 != 0) {
            val rotation = AffineTransform.getRotateInstance(-Math.toRadians(selected.angle))
            val end = rotation.deltaTransform(Point2D.Double(x, y), null)
            state.point = Point2D.Double(reference.x + end.x, reference.y + end.y)
        }
    }

    private fun graphics(canvas: BufferedImage, state: EmfState, base: AffineTransform): Graphics2D = canvas.createGraphics().apply {
        EmfPicture.hints(this)
        state.metaClip?.let { clip = it }; state.clip?.let { clip(it) }
        transform = state.transform(base)
    }

    private fun budget(valid: Boolean) {
        if (!valid) throw GenerationException(listOf(Diagnostic(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.PDF_CONVERSION_BYTE_BUDGET_WAS_EXCEEDED)))
    }

    private fun decode(bytes: ByteArray, encoding: String): String = try {
        java.nio.charset.Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { throw EmfFailure("Malformed image text encoding") }
}

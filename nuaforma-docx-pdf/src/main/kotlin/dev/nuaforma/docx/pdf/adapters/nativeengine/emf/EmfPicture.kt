package dev.nuaforma.docx.pdf.adapters.nativeengine.emf

import dev.nuaforma.contracts.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.*
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Bounded GDI vector playback. Unsupported records fail; no GDI code, escape or external resource is executed. */
internal class EmfPicture(private val context: GenerationContext, private val lookup: ((String, Boolean, Boolean) -> Font)?) {
    private var state = EmfState()
    private val objects = mutableMapOf<Int, EmfObject>()
    private val saved = mutableListOf<EmfState>()
    private var path: Path2D.Double? = null
    private var recording = false
    private var points = 0
    private var handles = 0
    private var clips = 0
    private var image: BufferedImage? = null
    private var base = AffineTransform()

    fun decode(bytes: ByteArray, width: Float, height: Float, inspect: Boolean): ByteArray? {
        check(bytes.size >= 88, "Truncated header")
        resource(bytes.size <= 4_194_304)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val firstSize = input.getInt(4)
        check(firstSize in 88..bytes.size && firstSize % 4 == 0 && input.getInt(0) == 1, "Invalid header")
        val header = EmfRecord(1, 0, bytes.copyOfRange(0, firstSize))
        header.require(header.int(40) == 0x464d4520 && header.int(44) == 0x10000 && header.int(48) == bytes.size, "Invalid header identity")
        val records = header.int(52)
        handles = header.short(56) and 0xffff
        header.require(records >= 2 && handles >= 1, "Invalid declared record or handle count")
        resource(records <= 20_000 && handles <= 256)
        header.require(header.short(58) == 0 && header.int(68) == 0, "Unsupported palette or reserved header")
        val descriptionLength = header.int(60)
        val descriptionOffset = header.int(64)
        header.require(descriptionLength in 0..1024 && (descriptionLength == 0 && descriptionOffset == 0 ||
            descriptionLength > 0 && descriptionOffset >= 88 && descriptionOffset.toLong() + descriptionLength * 2 <= firstSize), "Invalid header description range")
        if (firstSize >= 100) header.require(header.int(88) == 0 && header.int(92) == 0 && header.int(96) == 0, "OpenGL/pixel formats are unsupported")
        configure(header, width, height, inspect)
        try {
            var offset = firstSize
            repeat(records - 1) { index ->
                context.checkActive()
                check(offset.toLong() + 8 <= bytes.size, "Truncated record")
                val type = input.getInt(offset)
                val size = input.getInt(offset + 4)
                check(size >= 8 && size % 4 == 0 && offset.toLong() + size <= bytes.size, "Invalid record size")
                val record = EmfRecord(type, offset, bytes.copyOfRange(offset, offset + size))
                if (type == 14) {
                    record.require(index == records - 2 && offset + size == bytes.size && size >= 20 &&
                        record.int(8) == 0 && record.int(16) == size, "Invalid end record")
                } else {
                    record.require(index < records - 2, "Missing end record")
                    dispatch(record)
                }
                offset += size
            }
            check(offset == bytes.size && !recording, "Incomplete metafile")
            return image?.let(::png)
        } finally { image?.flush(); image = null }
    }

    private fun configure(header: EmfRecord, width: Float, height: Float, inspect: Boolean) {
        val pixelWidth = ceil(width.toDouble() * 4).toLong()
        val pixelHeight = ceil(height.toDouble() * 4).toLong()
        resource(width.isFinite() && height.isFinite() && pixelWidth in 1..4096 && pixelHeight in 1..4096 && pixelWidth * pixelHeight <= 4_194_304)
        val px = header.int(72).toDouble(); val py = header.int(76).toDouble()
        val mmx = header.int(80).toDouble(); val mmy = header.int(84).toDouble()
        header.require(px in 1.0..1_000_000.0 && py in 1.0..1_000_000.0 && mmx in 1.0..100_000.0 && mmy in 1.0..100_000.0, "Invalid physical device")
        val left = header.int(24) * px / mmx / 100
        val top = header.int(28) * py / mmy / 100
        val frameWidth = (header.int(32).toLong() - header.int(24)) * px / mmx / 100
        val frameHeight = (header.int(36).toLong() - header.int(28)) * py / mmy / 100
        header.require(frameWidth in .001..1_000_000.0 && frameHeight in .001..1_000_000.0 && abs(left) <= 1_000_000 && abs(top) <= 1_000_000, "Invalid image frame")
        base = AffineTransform.getScaleInstance(pixelWidth / frameWidth, pixelHeight / frameHeight).apply { translate(-left, -top) }
        if (!inspect) image = BufferedImage(pixelWidth.toInt(), pixelHeight.toInt(), BufferedImage.TYPE_INT_ARGB)
    }

    private fun dispatch(record: EmfRecord) {
        when (record.type) {
            in 2..8, in 85..91 -> poly(record)
            27 -> {
                resource(++points <= 32_000)
                state.point = record.point(8)
                if (recording) requireNotNullPath().moveTo(state.point.x, state.point.y)
            }
            54 -> line(record)
            42, 43, 44 -> figure(record)
            in 59..64, 67, 68 -> path(record)
            37, 38, 39, 40, 82, 95 -> objectRecord(record)
            81, 83, 84 -> content(record)
            else -> stateRecord(record)
        }
    }

    private fun stateRecord(r: EmfRecord) {
        when (r.type) {
            9 -> { if (state.mapMode == 8) state.windowExtent = extent(r) else r.point(8); checkMapping(r) }
            10 -> state.window = r.point(8)
            11 -> { if (state.mapMode == 8) state.viewportExtent = extent(r) else r.point(8); checkMapping(r) }
            12 -> state.viewport = r.point(8)
            16 -> r.require(r.int(8) == 0, "Unsupported mapper flags")
            17 -> {
                r.require(r.int(8) in setOf(1, 8), "Unsupported map mode")
                state.mapMode = r.int(8)
                if (state.mapMode == 1) {
                    state.windowExtent = Point2D.Double(1.0, 1.0)
                    state.viewportExtent = Point2D.Double(1.0, 1.0)
                }
            }
            18 -> { r.require(r.int(8) in 1..2, "Invalid background mode"); state.backgroundMode = r.int(8) }
            19 -> { r.require(r.int(8) in 1..2, "Invalid polygon winding"); state.winding = if (r.int(8) == 1) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO }
            20 -> r.require(r.int(8) == 13, "Only copy-pen compositing is qualified")
            21 -> { r.require(r.int(8) in 1..4, "Invalid bitmap stretch mode"); state.stretchMode = r.int(8) }
            22 -> {
                val alignment = r.int(8)
                r.require(alignment and 0x1f.inv() == 0 && (alignment and 6) in setOf(0, 2, 6) &&
                    (alignment and 24) in setOf(0, 8, 24), "Unsupported text alignment")
                state.textAlign = alignment
            }
            24 -> state.textColor = color(r.int(8))
            25 -> state.background = color(r.int(8))
            26 -> offsetClip(r)
            28 -> {
                state.clip?.let { selected ->
                    val previous = state.metaClip
                    if (previous == null) state.metaClip = Area(selected) else previous.intersect(selected)
                }
                state.clip = null
            }
            29, 30 -> clipRectangle(r)
            31, 32 -> scaleMapping(r)
            33 -> { resource(saved.size < 32); saved += state.copy() }
            34 -> restore(r)
            35 -> state.world = transform(r)
            36 -> modifyTransform(r)
            58 -> { val m = r.float(8); r.require(m in 1.0..1000.0, "Invalid miter limit"); state.miter = m.toFloat() }
            70 -> r.require(r.int(8) == 0, "EMF comments/extensions are not qualified")
            else -> throw EmfFailure("Unsupported record ${r.type} at ${r.offset}")
        }
    }

    private fun extent(r: EmfRecord): Point2D.Double = r.point(8).also { r.require(it.x != 0.0 && it.y != 0.0, "Zero mapping extent") }

    private fun checkMapping(r: EmfRecord) {
        r.require(listOf(state.viewportExtent.x / state.windowExtent.x, state.viewportExtent.y / state.windowExtent.y).all {
            it.isFinite() && abs(it) in .000001..1_000_000.0 }, "Unbounded mapping scale")
    }

    private fun scaleMapping(r: EmfRecord) {
        val x = r.int(8).toDouble(); val xd = r.int(12).toDouble()
        val y = r.int(16).toDouble(); val yd = r.int(20).toDouble()
        r.require(x != 0.0 && xd != 0.0 && y != 0.0 && yd != 0.0, "Invalid mapping fraction")
        if (state.mapMode != 8) return
        val previous = if (r.type == 31) state.viewportExtent else state.windowExtent
        val scaled = Point2D.Double(previous.x * x / xd, previous.y * y / yd)
        if (r.type == 31) state.viewportExtent = scaled else state.windowExtent = scaled
        checkMapping(r)
    }

    private fun offsetClip(r: EmfRecord) {
        val delta = state.transform(base).deltaTransform(r.point(8), null)
        state.clip?.transform(AffineTransform.getTranslateInstance(delta.x, delta.y))
    }

    private fun transform(r: EmfRecord) = AffineTransform(r.float(8), r.float(12), r.float(16), r.float(20), r.float(24), r.float(28)).also {
        r.require(abs(it.determinant) in .000000000001..1_000_000_000_000.0, "Singular or unbounded transform")
    }

    private fun modifyTransform(r: EmfRecord) {
        when (r.int(32)) {
            1 -> state.world = AffineTransform()
            // GDI stores row-vector matrices; Java2D uses the transposed column-vector convention.
            2 -> state.world.concatenate(transform(r))
            3 -> state.world.preConcatenate(transform(r))
            4 -> state.world = transform(r)
            else -> throw EmfFailure("Invalid world transform mode")
        }
        r.require(abs(state.world.determinant) in .000000000001..1_000_000_000_000.0, "Unbounded world transform")
        val components = DoubleArray(6).also(state.world::getMatrix)
        r.require(components.all { it.isFinite() && abs(it) <= 1_000_000 }, "Unbounded world transform components")
    }

    private fun restore(r: EmfRecord) {
        val requested = r.int(8)
        val index = if (requested < 0) saved.size.toLong() + requested else requested.toLong() - 1
        r.require(index >= 0 && index < saved.size, "Invalid saved context")
        state = saved[index.toInt()]
        while (saved.size > index) saved.removeAt(saved.lastIndex)
    }

    private fun objectRecord(r: EmfRecord) {
        val handle = r.int(8)
        if (r.type == 37) { select(r, handle); return }
        r.require(handle in 1 until handles, "Invalid object handle")
        if (r.type == 40) { r.require(objects.remove(handle) != null, "Unknown deleted object"); return }
        r.require(handle !in objects, "Duplicate object handle")
        objects[handle] = when (r.type) {
            38 -> pen(r, false)
            95 -> pen(r, true)
            39 -> { r.require(r.int(12) in 0..1 && r.int(20) == 0, "Unsupported brush"); EmfBrush(if (r.int(12) == 1) null else color(r.int(16))) }
            else -> EmfContent.font(r)
        }
    }

    private fun select(r: EmfRecord, handle: Int) {
        val selected = if (handle < 0) when (handle and 0x7fffffff) {
            0 -> EmfBrush(Color.WHITE); 1 -> EmfBrush(Color(192, 192, 192)); 2 -> EmfBrush(Color(128, 128, 128))
            3 -> EmfBrush(Color(64, 64, 64)); 4 -> EmfBrush(Color.BLACK); 5 -> EmfBrush(null)
            6 -> EmfPen(Color.WHITE); 7 -> EmfPen(Color.BLACK); 8 -> EmfPen(null)
            else -> throw EmfFailure("Unsupported stock object")
        } else objects[handle] ?: throw EmfFailure("Unknown selected object")
        when (selected) { is EmfPen -> state.pen = selected; is EmfBrush -> state.brush = selected; is EmfFont -> state.font = selected }
        r.require(handle != 0, "Invalid selected object")
    }

    private fun pen(r: EmfRecord, extended: Boolean): EmfPen {
        val at = if (extended) 28 else 12
        val flags = r.int(at); val style = flags and 15
        r.require(style in 0..5 || extended && style == 7, "Unsupported pen style")
        r.require(flags and 0x1330f.inv() == 0, "Unsupported pen flags")
        val width = r.int(at + 4)
        r.require(width in 0..1_000_000, "Invalid pen width")
        val rgb = if (extended) {
            r.require((12..24 step 4).all { r.int(it) == 0 } && r.int(36) == 0 && r.int(44) == 0, "Pattern pens are unsupported")
            color(r.int(40))
        } else { r.require(r.int(20) == 0, "Invalid pen height"); color(r.int(24)) }
        val entries = if (extended) r.int(48) else 0
        r.require(entries >= 0, "Invalid pen dash count")
        resource(entries <= 32)
        val dash = if (style == 7) List(entries) { r.int(52 + it * 4).also { v -> r.require(v in 1..1_000_000, "Invalid pen dash") }.toFloat() } else null
        r.require(style != 7 || !dash.isNullOrEmpty(), "Missing custom dash")
        val cap = when (flags and 0xf00) { 0 -> BasicStroke.CAP_ROUND; 0x100 -> BasicStroke.CAP_SQUARE; 0x200 -> BasicStroke.CAP_BUTT; else -> throw EmfFailure("Unsupported pen cap") }
        val join = when (flags and 0xf000) { 0 -> BasicStroke.JOIN_ROUND; 0x1000 -> BasicStroke.JOIN_BEVEL; 0x2000 -> BasicStroke.JOIN_MITER; else -> throw EmfFailure("Unsupported pen join") }
        return EmfPen(if (style == 5) null else rgb, maxOf(1, width).toFloat(), style, cap, join, flags and 0x10000 != 0 || width > 1, dash)
    }

    private fun poly(r: EmfRecord) {
        val small = r.type >= 85
        val type = if (small) r.type - 83 else r.type
        val groups = if (type in 7..8) r.int(24) else 1
        val count = r.int(if (type in 7..8) 28 else 24)
        r.require(groups > 0 && count > 0, "Invalid polygon counts")
        resource(groups <= 4096 && count <= 16_000 && points.toLong() + count <= 32_000)
        points += count
        val counts = if (type in 7..8) List(groups) { r.int(32 + it * 4).also { n -> r.require(n > 0, "Empty polygon") } } else listOf(count)
        r.require(counts.sumOf { it.toLong() } == count.toLong(), "Polygon point count mismatch")
        var offset = if (type in 7..8) 32 + groups * 4 else 28
        val shape = Path2D.Double(state.winding)
        for (length in counts) {
            val values = List(length) { r.point(offset, small).also { offset += if (small) 4 else 8 } }
            val to = type in 5..6
            if (to) shape.moveTo(state.point.x, state.point.y) else shape.moveTo(values.first().x, values.first().y)
            val start = if (to) 0 else 1
            if (type == 2 || type == 5) {
                r.require((length - start) % 3 == 0, "Incomplete Bezier curve")
                for (index in start until length step 3) shape.curveTo(values[index].x, values[index].y, values[index + 1].x, values[index + 1].y, values[index + 2].x, values[index + 2].y)
            } else for (point in values.drop(start)) shape.lineTo(point.x, point.y)
            if (type == 3 || type == 8) shape.closePath()
            if (to) state.point = values.last()
        }
        if (recording) requireNotNullPath().append(shape, type in 5..6) else paint(shape, type == 3 || type == 8, true)
    }

    private fun line(r: EmfRecord) {
        resource(++points <= 32_000)
        val end = r.point(8)
        val shape = Path2D.Double().apply { moveTo(state.point.x, state.point.y); lineTo(end.x, end.y) }
        if (recording) {
            if (requireNotNullPath().currentPoint == null) requireNotNullPath().moveTo(state.point.x, state.point.y)
            requireNotNullPath().lineTo(end.x, end.y)
        } else paint(shape, false, true)
        state.point = end
    }

    private fun rectangle(r: EmfRecord, at: Int = 8): Rectangle2D.Double {
        val a = r.point(at); val b = r.point(at + 8)
        r.require(b.x > a.x && b.y > a.y, "Invalid rectangle")
        return Rectangle2D.Double(a.x, a.y, b.x - a.x, b.y - a.y)
    }

    private fun figure(r: EmfRecord) {
        val rect = rectangle(r)
        val shape = when (r.type) {
            42 -> Ellipse2D.Double(rect.x, rect.y, rect.width, rect.height)
            44 -> r.point(24).let { RoundRectangle2D.Double(rect.x, rect.y, rect.width, rect.height, abs(it.x), abs(it.y)) }
            else -> rect
        }
        emit(shape, true, true)
    }

    private fun path(r: EmfRecord) {
        when (r.type) {
            59 -> { r.require(!recording, "Nested paths"); path = Path2D.Double(state.winding); recording = true }
            60 -> { r.require(recording, "Path not started"); recording = false }
            61 -> { r.require(recording && path?.currentPoint != null, "No current figure"); path!!.closePath() }
            68 -> { path = null; recording = false }
            67 -> { r.require(!recording, "Path is unfinished"); clip(requireNotNullPath(), r.int(8)); path = null }
            else -> { r.require(!recording, "Path is unfinished"); paint(requireNotNullPath(), r.type in 62..63, r.type in 63..64); path = null }
        }
    }

    private fun requireNotNullPath(): Path2D.Double = path ?: throw EmfFailure("Missing path")

    private fun emit(shape: Shape, fill: Boolean, stroke: Boolean) {
        if (recording) requireNotNullPath().append(shape, false) else paint(shape, fill, stroke)
    }

    private fun paint(shape: Shape, fill: Boolean, stroke: Boolean) {
        val canvas = image ?: return
        val matrix = state.transform(base)
        val graphics = canvas.createGraphics()
        try {
            hints(graphics); state.metaClip?.let { graphics.clip = it }; state.clip?.let { graphics.clip(it) }
            if (fill) state.brush.color?.let { graphics.color = it; graphics.fill(matrix.createTransformedShape(shape)) }
            if (stroke) state.pen.color?.let {
                graphics.color = it
                if (state.pen.geometric) { graphics.transform = matrix; graphics.stroke = state.pen.stroke(1f, state.miter); graphics.draw(shape) }
                else { graphics.stroke = state.pen.stroke(sqrt(abs(base.determinant)).toFloat(), state.miter); graphics.draw(matrix.createTransformedShape(shape)) }
            }
        } finally { graphics.dispose() }
    }

    private fun clipRectangle(r: EmfRecord) = clip(rectangle(r), if (r.type == 30) 1 else 4)

    private fun clip(shape: Shape, mode: Int) {
        resource(++clips <= 64)
        val segments = shape.getPathIterator(null)
        var count = 0
        while (!segments.isDone) { resource(++count <= 512); segments.next() }
        val area = Area(state.transform(base).createTransformedShape(shape))
        val current = state.clip ?: image?.let { Area(Rectangle(0, 0, it.width, it.height)) } ?: Area(Rectangle2D.Double(-1_000_000.0, -1_000_000.0, 2_000_000.0, 2_000_000.0))
        when (mode) { 1 -> current.intersect(area); 2 -> current.add(area); 3 -> current.exclusiveOr(area); 4 -> current.subtract(area); 5 -> { state.clip = area; return }; else -> throw EmfFailure("Unsupported clip combination") }
        state.clip = current
    }

    private fun content(r: EmfRecord) {
        val canvas = image
        if (r.type == 81) EmfContent.bitmap(r, canvas, state, base, context)
        else EmfContent.text(r, canvas, state, base, lookup, context)
    }

    private fun png(canvas: BufferedImage): ByteArray {
        return EmfPng.encode(canvas, context)
    }

    private fun resource(valid: Boolean) {
        if (!valid) throw GenerationException(listOf(Diagnostic(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.PDF_CONVERSION_BYTE_BUDGET_WAS_EXCEEDED)))
    }

    private fun check(valid: Boolean, reason: String) {
        if (!valid) throw EmfFailure(reason)
    }

    companion object {

        fun color(value: Int): Color {
            if (value ushr 24 != 0) throw EmfFailure("Palette colors are unsupported")
            return Color(value and 255, value ushr 8 and 255, value ushr 16 and 255)
        }

        fun hints(g: Graphics2D) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        }
    }
}

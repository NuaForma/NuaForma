package dev.nuaforma.docx.pdf.adapters.nativeengine.emf

import java.awt.BasicStroke
import java.awt.Color
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.Point2D

internal sealed interface EmfObject
internal data class EmfBrush(val color: Color?) : EmfObject
internal data class EmfPen(val color: Color?, val width: Float = 1f, val style: Int = 0,
    val cap: Int = BasicStroke.CAP_ROUND, val join: Int = BasicStroke.JOIN_ROUND,
    val geometric: Boolean = false, val dash: List<Float>? = null) : EmfObject {

    fun stroke(scale: Float, miter: Float): BasicStroke {
        val pattern = dash ?: when (style) {
            1 -> listOf(3f, 1f); 2 -> listOf(1f, 1f); 3 -> listOf(3f, 1f, 1f, 1f)
            4 -> listOf(3f, 1f, 1f, 1f, 1f, 1f); else -> null
        }
        return BasicStroke(maxOf(.01f, width * scale), cap, join, miter, pattern?.map { maxOf(.01f, it * scale) }?.toFloatArray(), 0f)
    }
}
internal data class EmfFont(val family: String, val height: Int, val bold: Boolean, val italic: Boolean,
    val underline: Boolean, val strike: Boolean, val angle: Double, val charset: Int) : EmfObject

/** Selected objects and clipping are copied with each saved device context. */
internal class EmfState {
    var pen = EmfPen(Color.BLACK)
    var brush = EmfBrush(Color.WHITE)
    var font: EmfFont? = null
    var world = AffineTransform()
    var window = Point2D.Double()
    var viewport = Point2D.Double()
    var windowExtent = Point2D.Double(1.0, 1.0)
    var viewportExtent = Point2D.Double(1.0, 1.0)
    var mapMode = 1
    var point = Point2D.Double()
    var winding = Path2D.WIND_EVEN_ODD
    var clip: Area? = null
    var metaClip: Area? = null
    var miter = 10f
    var textColor = Color.BLACK
    var background = Color.WHITE
    var backgroundMode = 2
    var textAlign = 0
    var stretchMode = 3

    fun transform(base: AffineTransform): AffineTransform = AffineTransform(base).apply {
        translate(viewport.x, viewport.y)
        scale(viewportExtent.x / windowExtent.x, viewportExtent.y / windowExtent.y)
        translate(-window.x, -window.y)
        concatenate(world)
    }

    fun copy(): EmfState = EmfState().also {
        it.pen = pen; it.brush = brush; it.font = font
        it.world = AffineTransform(world); it.window = window.clone() as Point2D.Double
        it.viewport = viewport.clone() as Point2D.Double
        it.windowExtent = windowExtent.clone() as Point2D.Double; it.viewportExtent = viewportExtent.clone() as Point2D.Double
        it.point = point.clone() as Point2D.Double; it.winding = winding; it.clip = clip?.let(::Area); it.metaClip = metaClip?.let(::Area)
        it.miter = miter; it.textColor = textColor; it.background = background
        it.backgroundMode = backgroundMode; it.textAlign = textAlign; it.stretchMode = stretchMode; it.mapMode = mapMode
    }
}

package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.DocxFailure
import org.docx4j.XmlUtils
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.wml.*
import kotlin.math.roundToLong

/** Inline opaque DrawingML strokes. All XML substitutions are bounded numbers or RGB digits. */
internal class WordVectors(document: WordprocessingMLPackage, private val identities: WordIdentities,
                           private val context: GenerationContext) {
    private val resolver by lazy { document.mainDocumentPart.propertyResolver }
    private var points = 0L

    fun drawing(vector: ContentResult.Vector, anchor: R, paragraph: P?): Drawing {
        context.checkActive()
        points += vector.paths.sumOf { it.points.size }
        if (points > minOf(20_000L, context.limits.maxExpandedBytes / 64))
            DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.INLINE_VECTOR_GEOMETRY_EXCEEDS_LIMITS)
        val properties = resolver.getEffectiveRPr(anchor.rPr, paragraph?.pPr, paragraph?.let { resolver.cellContextOf(it) })
        val declaredSize = properties?.sz?.`val` ?: java.math.BigInteger.valueOf(22)
        if (declaredSize < java.math.BigInteger.valueOf(2) || declaredSize > java.math.BigInteger.valueOf(2500)) unsupportedStyle()
        val halfPoints = declaredSize.toInt()
        val size = (halfPoints * 0.4 * 12700).roundToLong()
        val color = properties?.color?.`val`?.takeIf { it != "auto" } ?: "000000"
        if (!color.matches(Regex("[0-9a-fA-F]{6}")) || properties?.color?.let {
            it.themeColor != null || it.themeTint != null || it.themeShade != null
        } == true) unsupportedStyle()
        val id = identities.nextDrawing()
        val strokeWidth = strokeWidth(vector, properties?.b?.isVal == true)
        val paths = vector.paths.joinToString("") { path ->
            """<a:path w="1000" h="1000" fill="none" stroke="1" extrusionOk="0">""" +
                path.points.mapIndexed { index, p ->
                    val command = if (index == 0) "moveTo" else "lnTo"
                    """<a:$command><a:pt x="${p.x}" y="${p.y}"/></a:$command>"""
                }.joinToString("") + (if (path.closed) "<a:close/>" else "") + "</a:path>"
        }
        val xml = """<w:drawing xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">
            <wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$size" cy="$size"/>
            <wp:docPr id="$id" name="Vector$id"/><a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">
            <wps:wsp><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$size" cy="$size"/></a:xfrm>
            <a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l="0" t="0" r="1000" b="1000"/>
            <a:pathLst>$paths</a:pathLst></a:custGeom><a:noFill/>
            <a:ln w="${(size * strokeWidth / 1000.0).roundToLong()}" cap="rnd" cmpd="sng" algn="ctr">
            <a:solidFill><a:srgbClr val="$color"/></a:solidFill><a:prstDash val="solid"/><a:round/></a:ln>
            </wps:spPr><wps:bodyPr/></wps:wsp></a:graphicData></a:graphic></wp:inline></w:drawing>"""
        return XmlUtils.unwrap(XmlUtils.unmarshalString(xml)) as Drawing
    }

    private fun unsupportedStyle(): Nothing = DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.UNSUPPORTED_INLINE_VECTOR_STYLE)

    private fun strokeWidth(vector: ContentResult.Vector, bold: Boolean): Int {
        if (!bold) return vector.strokeWidth
        // Keep trusted custom vector strokes inside their declared box too. Checkbox paths
        // have a 50-unit margin, allowing the full 70-to-100 bold weight increase.
        val clearance = vector.paths.minOf { path -> path.points.minOf { point ->
            2 * minOf(point.x, point.y, 1000 - point.x, 1000 - point.y)
        } }
        return minOf(100, clearance, (vector.strokeWidth * 10 + 3) / 7)
    }

}

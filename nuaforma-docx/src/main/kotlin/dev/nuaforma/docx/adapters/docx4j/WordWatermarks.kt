package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.DocxFailure
import org.docx4j.TraversalUtil
import org.docx4j.XmlUtils
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.openpackaging.parts.PartName
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart
import org.docx4j.wml.*
import java.math.BigDecimal
import java.util.UUID

/** Place resolved background shapes without reevaluation; never delete existing header objects. */
internal class WordWatermarks(private val context: GenerationContext) {
    private var visits = 0L

    fun apply(document: WordprocessingMLPackage, watermark: TextWatermark?) {
        if (watermark == null) return
        context.checkActive()
        val main = document.mainDocumentPart
        val body = main.jaxbElement.body
        val sections = body.content.map { XmlUtils.unwrap(it) }.filterIsInstance<P>().mapNotNull { it.pPr?.sectPr }.toMutableList()
        walk(body) { node -> if (node is P && node.pPr?.sectPr != null && node.pPr.sectPr !in sections) unsupported(RejectionReason.WATERMARKS_REQUIRE_SECTION_BREAKS_IN_BODY_PARAGRAPHS) }
        val finalSection = body.sectPr ?: SectPr().also { body.sectPr = it }
        sections.add(finalSection)
        val even = main.documentSettingsPart?.jaxbElement?.evenAndOddHeaders?.isVal == true
        val inherited = mutableMapOf<HdrFtrRef, HeaderPart>()
        val decorated = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<HeaderPart, Boolean>())
        var pageWidth = 612.0
        var pageHeight = 792.0
        var smallest = minOf(pageWidth, pageHeight)
        sections.forEach { section ->
            section.pgSz?.let { size ->
                size.w?.let { pageWidth = it.toDouble() / 20 }
                size.h?.let { pageHeight = it.toDouble() / 20 }
            }
            if (!pageWidth.isFinite() || !pageHeight.isFinite() || pageWidth <= 0 || pageHeight <= 0) unsupported(RejectionReason.WATERMARK_PAGE_GEOMETRY_MUST_BE_POSITIVE_AND_FINITE)
            smallest = minOf(smallest, pageWidth, pageHeight)
        }
        val width = minOf(400.0, smallest * 0.65)
        val font = main.styleDefinitionsPart?.jaxbElement?.docDefaults?.rPrDefault?.rPr?.rFonts?.let { it.ascii ?: it.hAnsi } ?: "Arial"
        sections.forEach { section ->
            context.checkActive()
            val references = section.getEGHdrFtrReferences().filterIsInstance<HeaderReference>()
            if (references.map { it.type }.distinct().size != references.size) unsupported(RejectionReason.DUPLICATE_HEADER_KINDS_CANNOT_BE_WATERMARKED_SAFELY)
            references.forEach { reference ->
                val type = reference.type ?: unsupported(RejectionReason.WATERMARK_HEADER_REFERENCES_NEED_AN_EXPLICIT_KIND)
                val part = main.relationshipsPart?.getPart(reference.id) as? HeaderPart ?: unsupported(RejectionReason.WATERMARK_HEADER_REFERENCE_DOES_NOT_POINT_TO_A_HEADER)
                inherited[type] = part
            }
            val active = listOf(HdrFtrRef.DEFAULT) + (if (even) listOf(HdrFtrRef.EVEN) else emptyList()) +
                (if (section.titlePg?.isVal == true) listOf(HdrFtrRef.FIRST) else emptyList())
            active.forEach { type ->
                val header = inherited[type] ?: run {
                    // A new anchor must not move the body where an absent header previously reserved no space.
                    val margins = section.pgMar
                    if (margins?.header != null && margins.top != null && margins.header >= margins.top) unsupported(RejectionReason.WATERMARKS_CANNOT_CREATE_A_HEADER_AT_OR_BELOW_THE)
                    var name: PartName
                    do { name = PartName("/word/nf-header-${UUID.randomUUID()}.xml") } while (document.parts.parts.containsKey(name))
                    val created = HeaderPart(name).apply { jaxbElement = Hdr() }
                    val relationship = main.addTargetPart(created)
                    section.getEGHdrFtrReferences().add(HeaderReference().apply { this.type = type; id = relationship.id })
                    inherited[type] = created
                    created
                }
                if (decorated.add(header)) decorate(header, watermark, width, font)
            }
        }
    }

    private fun decorate(header: HeaderPart, watermark: TextWatermark, width: Double, font: String) {
        var anchor: P? = null
        walk(header.jaxbElement) { if (anchor == null && it is P) anchor = it }
        if (anchor == null) {
            anchor = XmlUtils.unwrap(XmlUtils.unmarshalString("""<w:p xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:pPr><w:spacing w:before="0" w:after="0" w:line="1" w:lineRule="exact"/></w:pPr></w:p>""")) as P
            header.jaxbElement.content.add(anchor)
        }
        val id = "NFWatermark${UUID.randomUUID().toString().replace("-", "") }"
        val rotation = ((watermark.rotation % 360) + 360) % 360
        val style = "position:absolute;left:0;top:0;margin-left:0;margin-top:0;width:${number(width)}pt;height:${number(width / 4)}pt;rotation:${number(rotation)};z-index:-251658752;mso-position-horizontal:center;mso-position-horizontal-relative:page;mso-position-vertical:center;mso-position-vertical-relative:page;mso-wrap-style:none"
        val fontStyle = "font-family:\"${font.replace("\\", "\\\\").replace("\"", "\\\"")}\";font-size:1pt"
        val xml = """<w:r xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:w10="urn:schemas-microsoft-com:office:word"><w:rPr><w:noProof/><w:sz w:val="2"/></w:rPr><w:pict>
            <v:shapetype id="${id}Type" coordsize="21600,21600" o:spt="136" adj="10800" path="m@7,l@8,m@5,21600l@6,21600e"><v:formulas><v:f eqn="sum #0 0 10800"/><v:f eqn="prod #0 2 1"/><v:f eqn="sum 21600 0 @1"/><v:f eqn="sum 0 0 @2"/><v:f eqn="sum 21600 0 @3"/><v:f eqn="if @0 @3 0"/><v:f eqn="if @0 21600 @1"/><v:f eqn="if @0 0 @2"/><v:f eqn="if @0 @4 21600"/><v:f eqn="mid @5 @6"/><v:f eqn="mid @8 @5"/><v:f eqn="mid @7 @8"/><v:f eqn="mid @6 @7"/><v:f eqn="sum @6 0 @5"/></v:formulas><v:path textpathok="t"/><v:textpath on="t" fitshape="t"/></v:shapetype>
            <v:shape id="$id" type="#${id}Type" style="${escape(style)}" fillcolor="${watermark.color}" stroked="f" o:allowincell="f"><v:fill opacity="${number(watermark.opacity)}"/><v:textpath string="${escape(watermark.text)}" style="${escape(fontStyle)}"/><w10:wrap type="none" anchorx="page" anchory="page"/></v:shape>
            </w:pict></w:r>"""
        context.checkActive()
        anchor.content.add(XmlUtils.unwrap(XmlUtils.unmarshalString(xml)))
    }

    private fun walk(raw: Any, depth: Int = 1, inspect: (Any) -> Unit) {
        context.checkActive()
        if (++visits > 1_000_000 || depth > 128) DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.WATERMARK_TRAVERSAL_EXCEEDS_PROCESSING_LIMITS)
        val node = XmlUtils.unwrap(raw)
        inspect(node)
        TraversalUtil.getChildrenImpl(node)?.toList()?.forEach { walk(it, depth + 1, inspect) }
    }

    private fun number(value: Double) = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun unsupported(reason: RejectionReason): Nothing = DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_OPTION, reason)
}

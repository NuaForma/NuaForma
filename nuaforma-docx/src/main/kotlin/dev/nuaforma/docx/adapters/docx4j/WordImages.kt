package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.internal.ImageContent
import org.docx4j.XmlUtils
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.openpackaging.parts.Part
import org.docx4j.openpackaging.parts.PartName
import org.docx4j.openpackaging.parts.WordprocessingML.ImagePngPart
import org.docx4j.wml.Drawing

/** Relationships are created on the owning document/header/footer part, never the main part by default. */
internal class WordImages(private val document: WordprocessingMLPackage, private val identities: WordIdentities,
                          private val context: GenerationContext) {
    private var serial = 0
    fun drawing(image: ImageContent, owner: Part): Drawing {
        context.checkActive()
        var name: PartName
        do { name = PartName("/word/media/nuaforma-image-${++serial}.png") } while (document.parts.parts.containsKey(name))
        val part = ImagePngPart(name).apply { setBinaryData(image.bytes()) }
        val relationship = owner.addTargetPart(part)
        val id = identities.nextDrawing()
        // All interpolated fields are generator-owned identifiers or validated positive dimensions.
        val xml = """<w:drawing xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
            <wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="${image.widthEmu}" cy="${image.heightEmu}"/>
            <wp:docPr id="$id" name="NuaFormaImage$id"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect="1"/></wp:cNvGraphicFramePr>
            <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic>
            <pic:nvPicPr><pic:cNvPr id="0" name="NuaFormaImage$id"/><pic:cNvPicPr/></pic:nvPicPr>
            <pic:blipFill><a:blip r:embed="${relationship.id}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
            <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${image.widthEmu}" cy="${image.heightEmu}"/></a:xfrm>
            <a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing>"""
        return XmlUtils.unwrap(XmlUtils.unmarshalString(xml)) as Drawing
    }
}

package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import javax.xml.namespace.QName

/** Validates saved image bullets as source metadata. The numbering reader qualifies visible levels separately. */
internal class WordPictureBulletMetadata(private val context: GenerationContext) {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val vml = "urn:schemas-microsoft-com:vml"
    private val office = "urn:schemas-microsoft-com:office:office"
    private val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val packageRel = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val word14 = "http://schemas.microsoft.com/office/word/2010/wordml"
    private val definitionsPart = "word/numbering.xml"
    private val relsPart = "word/_rels/numbering.xml.rels"
    private val pngHeader = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val idPattern = Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}")
    private val imagePath = Regex("media/[A-Za-z0-9_.-]+\\.(?i:png)")
    private val dimensions = Regex("width:[0-9]{1,4}(?:\\.[0-9]{1,4})?pt;height:[0-9]{1,4}(?:\\.[0-9]{1,4})?pt;visibility:visible;mso-wrap-style:square")

    fun normalize(roots: Map<String, XmlElement>, parts: Map<String, ByteArray>,
        reject: (String, XmlElement, String) -> Nothing) {
        val numbering = roots[definitionsPart]
        val links = roots[relsPart]
        val bullets = numbering?.elements().orEmpty().filter { it.name.localPart == "numPicBullet" }
        if (bullets.isEmpty() && links == null) {
            val orphan = numbering?.descendants()?.firstOrNull { it.name.localPart == "lvlPicBulletId" }
            if (orphan != null) reject(definitionsPart, orphan, "Picture-bullet level reference requires a definition")
            return
        }
        if (numbering == null || bullets.isEmpty()) reject(relsPart, requireNotNull(links), "Picture-bullet relationships require definitions")
        if (links == null) reject(definitionsPart, bullets.first(), "Picture-bullet definitions require owned relationships")
        val media = relationshipIds(links, parts, reject)
        val defined = mutableSetOf<Int>()
        val used = mutableSetOf<String>()
        for (bullet in bullets) {
            context.checkActive()
            val identifier = bullet.attribute(word, "numPicBulletId")?.toIntOrNull()
            if (bullet.name != QName(word, "numPicBullet") || bullet.attributes.size != 1 || identifier !in 0..1_000_000 ||
                !defined.add(requireNotNull(identifier)) || !blank(bullet)) reject(definitionsPart, bullet, "Invalid picture-bullet definition")
            val pict = bullet.elements().singleOrNull()?.takeIf { it.name == QName(word, "pict") && it.attributes.isEmpty() && blank(it) }
                ?: reject(definitionsPart, bullet, "Unsupported saved picture-bullet content")
            val shapes = pict.elements()
            val shape = shapes.lastOrNull()?.takeIf { it.name == QName(vml, "shape") }
                ?: reject(definitionsPart, pict, "Picture bullets require a saved VML bitmap")
            if (shapes.size > 2 || shapes.size == 2 && !validType(shapes.first()))
                reject(definitionsPart, pict, "Unsupported picture-bullet shape type")
            val relationship = shapeImage(shape) ?: reject(definitionsPart, shape, "Unsupported picture-bullet bitmap shape")
            if (relationship !in media) reject(definitionsPart, shape, "Missing picture-bullet bitmap relationship")
            used += relationship
        }
        if (used != media) reject(relsPart, links, "Unexpected unowned numbering image relationships")
        for (reference in numbering.descendants().filter { it.name.localPart == "lvlPicBulletId" }) {
            context.checkActive()
            if (reference.name != QName(word, "lvlPicBulletId") || reference.attributes.size != 1 || !leaf(reference) ||
                reference.attribute(word, "val")?.toIntOrNull() !in defined) {
                reject(definitionsPart, reference, "Invalid picture-bullet level reference")
            }
        }
        numbering.children.removeAll(bullets.toSet())
    }

    private fun relationshipIds(root: XmlElement, parts: Map<String, ByteArray>,
        reject: (String, XmlElement, String) -> Nothing): Set<String> {
        if (root.name != QName(packageRel, "Relationships") || root.attributes.isNotEmpty() || !blank(root))
            reject(relsPart, root, "Invalid picture-bullet relationships")
        val result = mutableSetOf<String>()
        for (item in root.elements()) {
            context.checkActive()
            val id = item.attribute("", "Id")
            val path = item.attribute("", "Target")?.takeIf(imagePath::matches)
            val media = path?.let { parts["word/$it"] }
            if (item.name != QName(packageRel, "Relationship") || item.attributes.size != 3 || !leaf(item) ||
                item.attribute("", "Type") != "$rel/image" || id == null || !idPattern.matches(id) || !result.add(id) ||
                media == null || media.size < pngHeader.size || !media.copyOf(pngHeader.size).contentEquals(pngHeader)) {
                reject(relsPart, item, "Picture-bullet relationships require existing internal PNG bitmaps")
            }
        }
        return result
    }

    private fun shapeImage(shape: XmlElement): String? {
        val attributes = setOf(QName("id"), QName("type"), QName("style"), QName(office, "bullet"))
        if (shape.attributes.keys != attributes || !blank(shape) || shape.attribute("", "id")?.let(idPattern::matches) != true ||
            shape.attribute("", "type") != "#_x0000_t75" || shape.attribute(office, "bullet") != "t" ||
            shape.attribute("", "style")?.let(dimensions::matches) != true) return null
        val parts = shape.elements()
        val image = parts.firstOrNull() ?: return null
        if (image.name != QName(vml, "imagedata") || !leaf(image) ||
            image.attributes.keys.any { it !in setOf(QName(rel, "id"), QName(office, "title")) } ||
            image.attribute(office, "title")?.isNotEmpty() == true ||
            parts.size !in 1..2 || parts.size == 2 && !validLock(parts.last())) return null
        return image.attribute(rel, "id")
    }

    private fun validType(type: XmlElement): Boolean {
        val expected = setOf(QName(word14, "anchorId"), QName("id"), QName("coordsize"), QName(office, "spt"),
            QName(office, "preferrelative"), QName("path"), QName("filled"), QName("stroked"))
        if (type.name != QName(vml, "shapetype") || type.attributes.keys.any { it !in expected } || !blank(type) ||
            type.attribute("", "id") != "_x0000_t75" || type.attribute(office, "spt") != "75") return false
        val nodes = type.elements()
        if (nodes.size != 4) return false
        val (stroke, formulas, path, lock) = nodes
        return stroke.name == QName(vml, "stroke") && stroke.attributes.keys == setOf(QName("joinstyle")) && leaf(stroke) &&
            formulas.name == QName(vml, "formulas") && formulas.attributes.isEmpty() && blank(formulas) && formulas.elements().size == 12 &&
            formulas.elements().all { it.name == QName(vml, "f") && it.attributes.keys == setOf(QName("eqn")) && leaf(it) } &&
            path.name == QName(vml, "path") && path.attributes.keys.all { it in setOf(QName(office, "extrusionok"),
                QName("gradientshapeok"), QName(office, "connecttype")) } && leaf(path) && validLock(lock)
    }

    private fun validLock(node: XmlElement): Boolean = node.name == QName(office, "lock") && leaf(node) &&
        node.attributes.keys.all { it in setOf(QName(vml, "ext"), QName("aspectratio")) }

    private fun blank(node: XmlElement) = node.children.filterIsInstance<XmlText>().all { it.text.isBlank() }

    private fun leaf(node: XmlElement) = node.elements().isEmpty() && blank(node)
}

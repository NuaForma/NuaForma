package dev.nuaforma.pdf.adapters.pdfbox

import dev.nuaforma.pdf.*
import org.apache.pdfbox.cos.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Distinct logical clusters may share a font glyph; give those aliases distinct PDF character IDs. */
internal class GlyphEncoding(private val fonts: Map<String, PDType0Font>, private val enabled: (String) -> Boolean,
    private val control: PdfRenderControl, private val bmpOnly: Boolean = false) {
    private data class Key(val glyph: Int, val text: String)
    private data class Alias(val glyph: Int, val width: Float)
    private class Mapping(val firstAlias: Int) {
        val codes = mutableMapOf<Key, Int>()
        val unicode = linkedMapOf<Int, String>()
        val aliases = linkedMapOf<Int, Alias>()
        var nextAlias = firstAlias
    }
    private val mappings = mutableMapOf<String, Mapping>()

    private fun font(id: String) = fonts[id] ?: throw PdfRenderException(PdfFailureCode.FONT_UNAVAILABLE, "Referenced font is not supplied")

    fun record(fontId: String, shape: FontShaper.Shape) {
        val font = font(fontId)
        if (font.willBeSubset()) font.addGlyphsToSubset(shape.glyphs.map { it.id }.toSet())
        for (glyph in shape.glyphs) register(fontId, glyph.id, glyph.logical)
    }

    fun glyph(fontId: String, value: FontShaper.Glyph): ByteArray = font(fontId).encodeGlyphId(register(fontId, value.id, value.logical))

    fun normal(fontId: String, text: String): ByteArray {
        val font = font(fontId)
        if (!enabled(fontId)) return font.encode(text)
        val output = ByteArrayOutputStream(text.length * 2)
        for (point in text.codePoints().toArray()) {
            control.checkActive()
            val value = String(Character.toChars(point))
            val bytes = font.encode(value)
            check(bytes.size == 2) { "Expected an Identity-H TrueType font" }
            val gid = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
            output.write(font.encodeGlyphId(register(fontId, gid, value)))
        }
        return output.toByteArray()
    }

    private fun register(fontId: String, glyph: Int, text: String): Int {
        if (bmpOnly && text.any { Character.isSurrogate(it) })
            throw PdfRenderException(PdfFailureCode.UNSUPPORTED_TEXT, "PDF/A-1b requires BMP Unicode character mappings")
        val font = font(fontId)
        val mapping = mappings.getOrPut(fontId) { Mapping((font.descendantFont as PDCIDFontType2).trueTypeFont.numberOfGlyphs) }
        val key = Key(glyph, text)
        return mapping.codes.getOrPut(key) {
            val previous = mapping.unicode[glyph]
            val cid = if (previous == null || previous == text) glyph else {
                if (mapping.nextAlias > 65535) throw PdfRenderException(PdfFailureCode.RESOURCE_LIMIT, "Font character-alias budget exceeded")
                mapping.nextAlias++.also { mapping.aliases[it] = Alias(glyph, font.getWidth(glyph)) }
            }
            mapping.unicode[cid] = text
            cid
        }
    }

    /** Call after PDFBox has finalized its subset mapping, before saving the document. */
    fun finish(pdf: PDDocument) {
        for ((fontId, mapping) in mappings) {
            control.checkActive()
            val font = font(fontId)
            if (mapping.aliases.isNotEmpty()) extendAliases(pdf, font, mapping)
            val cmap = StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
                .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
                .append("/CMapName /NuaFormaUnicode def\n/CMapType 2 def\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
            for (group in mapping.unicode.entries.sortedBy { it.key }.chunked(100)) {
                control.checkActive()
                cmap.append(group.size).append(" beginbfchar\n")
                for ((cid, text) in group) cmap.append("<%04X> <%s>\n".format(cid,
                    text.toByteArray(StandardCharsets.UTF_16BE).joinToString("") { "%02X".format(it) }))
                cmap.append("endbfchar\n")
            }
            cmap.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
            font.cosObject.setItem(COSName.TO_UNICODE, stream(pdf, cmap.toString().toByteArray(StandardCharsets.US_ASCII)))
        }
    }

    private fun extendAliases(pdf: PDDocument, font: PDType0Font, mapping: Mapping) {
        val dictionary = font.descendantFont.cosObject
        val original = dictionary.getDictionaryObject(COSName.CID_TO_GID_MAP)
        val old = (original as? COSStream)?.createInputStream()?.use { it.readAllBytes() }
        val bytes = ByteArray(maxOf(old?.size ?: mapping.firstAlias * 2, mapping.nextAlias * 2))
        if (old != null) old.copyInto(bytes) else for (gid in 0 until mapping.firstAlias) put(bytes, gid, gid)
        val widths = dictionary.getCOSArray(COSName.W) ?: COSArray().also { dictionary.setItem(COSName.W, it) }
        for ((cid, alias) in mapping.aliases) {
            control.checkActive()
            val index = alias.glyph * 2
            val gid = if (old == null) alias.glyph else {
                if (index + 1 >= old.size) throw PdfRenderException(PdfFailureCode.INVALID_RESOURCE, "Missing subset glyph alias")
                ((old[index].toInt() and 255) shl 8) or (old[index + 1].toInt() and 255)
            }
            put(bytes, cid, gid)
            widths.add(COSInteger.get(cid.toLong()))
            widths.add(COSArray().also { it.add(COSFloat(alias.width)) })
        }
        dictionary.setItem(COSName.CID_TO_GID_MAP, stream(pdf, bytes))
        val descriptor = font.fontDescriptor.cosObject
        if (descriptor.getDictionaryObject(COSName.CID_SET) is COSStream) {
            // Match PDFBox's contiguous CID inventory, including .notdef holes in the extended map.
            // Merely adding alias bits leaves a gap and violates PDF/A-2b/3b's complete-CID requirement.
            val count = bytes.size / 2
            val bits = ByteArray((count + 7) / 8) { 255.toByte() }
            if (count % 8 != 0) bits[bits.lastIndex] = (255 shl (8 - count % 8)).toByte()
            descriptor.setItem(COSName.CID_SET, stream(pdf, bits))
        }
    }

    private fun put(bytes: ByteArray, cid: Int, gid: Int) {
        bytes[cid * 2] = (gid ushr 8).toByte()
        bytes[cid * 2 + 1] = gid.toByte()
    }

    private fun stream(pdf: PDDocument, bytes: ByteArray) = PDStream(pdf).also { stream ->
        stream.createOutputStream(COSName.FLATE_DECODE).use { it.write(bytes) }
    }

}

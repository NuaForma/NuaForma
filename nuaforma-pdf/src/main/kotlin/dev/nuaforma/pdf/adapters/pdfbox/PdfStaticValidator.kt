package dev.nuaforma.pdf.adapters.pdfbox

import dev.nuaforma.pdf.*
import org.apache.pdfbox.cos.*
import org.apache.pdfbox.io.RandomAccessReadBufferedFile
import org.apache.pdfbox.pdfparser.PDFParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap

/** Executed in a memory/time/filesystem-bounded child JVM, never in the engine JVM. */
internal object PdfStaticValidator {
    fun validate(path: Path, maximumDecodedBytes: Long, control: PdfRenderControl, version: String = "1.7") {
        require(maximumDecodedBytes > 0)
        require(version in setOf("1.4", "1.7"))
        control.checkActive()
        if (Files.size(path) > control.maxOutputBytes) limit()
        try {
            RandomAccessReadBufferedFile(path.toFile()).use { input ->
                PDFParser(input).parse(false).use { document ->
                    if (document.isEncrypted || document.version != version.toFloat()) invalid()
                    val objects = document.document.xrefTable.keys
                    if (objects.size > 250_000) limit()
                    val pending = ArrayDeque<Pair<COSBase, Int>>()
                    pending.add(document.document.trailer to 0)
                    for (key in objects) {
                        control.checkActive()
                        pending.add(document.document.getObjectFromPool(key) to 0)
                    }
                    val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
                    var decoded = 0L
                    var visited = 0
                    while (pending.isNotEmpty()) {
                        control.checkActive()
                        val (value, depth) = pending.removeLast()
                        if (!seen.add(value)) continue
                        if (++visited > 500_000 || depth > 128) limit()
                        when (value) {
                            is COSObject -> pending.add((value.`object` ?: invalid()) to depth + 1)
                            is COSArray -> {
                                if (value.size() > 100_000) limit()
                                for (entry in value) pending.add(entry to depth + 1)
                            }
                            is COSDictionary -> {
                                inspect(value)
                                if (value is COSStream && value.getNameAsString(COSName.SUBTYPE) != "Image") {
                                    value.createInputStream().use { stream ->
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            control.checkActive()
                                            val count = stream.read(buffer)
                                            if (count < 0) break
                                            if (count > maximumDecodedBytes - decoded) limit()
                                            decoded += count
                                        }
                                    }
                                }
                                for ((_, entry) in value.entrySet()) pending.add(entry to depth + 1)
                            }
                        }
                    }
                    var pages = 0
                    for (page in document.pages) {
                        control.checkActive()
                        if (++pages > 10_000) limit()
                        val box = page.mediaBox
                        if (!box.width.isFinite() || !box.height.isFinite() || box.width <= 0 || box.height <= 0 ||
                            box.width > 14_400 || box.height > 14_400) invalid()
                    }
                    if (pages == 0 || pages != document.numberOfPages) invalid()
                }
            }
        } catch (error: PdfRenderException) { throw error }
        catch (_: Exception) { invalid() }
    }

    private fun inspect(dictionary: COSDictionary) {
        // Static PDF output: no executable actions, forms, attachments or external streams.
        for (key in forbiddenKeys) if (dictionary.containsKey(COSName.getPDFName(key))) invalid()
        if (dictionary is COSStream && dictionary.containsKey(COSName.F)) invalid()
        dictionary.getCOSDictionary(COSName.FONT)?.let { fonts ->
            for (key in fonts.keySet()) {
                val font = fonts.getDictionaryObject(key) as? COSDictionary ?: invalid()
                if (font.getNameAsString(COSName.TYPE) != "Font") invalid()
            }
        }
        val type = dictionary.getNameAsString(COSName.TYPE)
        val subtype = dictionary.getNameAsString(COSName.SUBTYPE)
        if (type == "EmbeddedFile" || subtype in setOf("Widget", "RichMedia", "Movie", "Sound", "Screen", "FileAttachment")) invalid()
        val action = dictionary.getNameAsString(COSName.S)
        if (action != null && (type == "Action" || action in actionTypes)) {
            if (action == "URI") {
                val uri = dictionary.getString(COSName.URI) ?: invalid()
                val scheme = java.net.URI(uri).scheme?.lowercase()
                if (scheme !in setOf("https", "http", "mailto")) invalid()
            } else if (action != "GoTo") invalid()
        }
        if (type == "Font") {
            if (subtype == "Type0") {
                val descendants = dictionary.getCOSArray(COSName.DESCENDANT_FONTS) ?: invalid()
                if (descendants.size() != 1 || descendants.getObject(0) !is COSDictionary) invalid()
            } else {
                if (subtype !in setOf("Type1", "TrueType", "CIDFontType0", "CIDFontType2")) invalid()
                val descriptor = dictionary.getCOSDictionary(COSName.FONT_DESC) ?: invalid()
                val streams = listOf(COSName.FONT_FILE, COSName.FONT_FILE2, COSName.FONT_FILE3)
                    .mapNotNull { descriptor.getDictionaryObject(it) as? COSStream }
                if (streams.size != 1 || streams.single().length <= 0) invalid()
            }
        }
    }

    private val forbiddenKeys = setOf("OpenAction", "AA", "JavaScript", "JS", "AcroForm", "XFA", "EmbeddedFiles", "EF", "AF", "FFilter", "FDecodeParms")
    private val actionTypes = setOf("GoTo", "GoToR", "GoToE", "Launch", "Thread", "URI", "Sound", "Movie", "Hide", "Named", "SubmitForm", "ResetForm", "ImportData", "JavaScript", "SetOCGState", "Rendition", "Trans", "GoTo3DView")
    private fun invalid(): Nothing = throw PdfRenderException(PdfFailureCode.VALIDATION_FAILED, "Generated PDF failed static output validation")
    private fun limit(): Nothing = throw PdfRenderException(PdfFailureCode.RESOURCE_LIMIT, "PDF validation resource budget exceeded")
}

package dev.nuaforma.docx.pdf.adapters.ooxml

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.internal.PdfFailure
import dev.nuaforma.docx.pdf.internal.PdfOptions
import dev.nuaforma.docx.pdf.internal.PdfProfile
import dev.nuaforma.docx.pdf.internal.PdfStreams
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Rewrites only supported geometry/styles in a private, already generated DOCX. */
internal object ConversionCopy {
    data class Summary(val normalizedShapes: Int, val modernInlineTextBoxes: Boolean)
    fun prepare(source: Path, destination: Path, options: PdfOptions, context: GenerationContext): Summary {
        require(source.toAbsolutePath().normalize() != destination.toAbsolutePath().normalize())
        context.checkActive()
        ZipFile(source.toFile()).use { input ->
            val entries = input.entries().toList()
            if (entries.size > context.limits.maxArchiveEntries) limited()
            if (entries.map { it.name }.distinct().size != entries.size || entries.any {
                it.name.startsWith('/') || it.name.contains('\\') || it.name.split('/').any { piece -> piece == ".." || piece == "." }
            }) PdfFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.CONVERSION_PACKAGE_HAS_INVALID_ARCHIVE_ENTRIES)
            val xml = ConversionXml(context)
            val wordParts = entries.filter { it.name.startsWith("word/") && it.name.endsWith(".xml") }
            var id = 0L
            var rightMargin = Long.MAX_VALUE
            var expanded = 0L
            var modernInline = false
            fun bytes(entry: ZipEntry): ByteArray {
                val buffer = ByteArrayOutputStream()
                input.getInputStream(entry).use { PdfStreams.copy(it, buffer, minOf(32L * 1024 * 1024, context.limits.maxExpandedBytes), context) }
                return buffer.toByteArray()
            }
            for (entry in entries) {
                context.checkActive()
                if (entry.size < 0 || entry.size > context.limits.maxExpandedBytes - expanded) limited()
                expanded += entry.size
            }
            for (entry in wordParts) {
                val tree = xml.read(bytes(entry))
                ConversionSafety.validate(tree)
                if (options.profile == PdfProfile.PDFA_1B) Pdfa1Safety.validate(tree)
                for (element in tree.descendants()) {
                    if (element.name.namespaceURI == NativeTextBoxes.WP && element.name.localPart == "inline" &&
                        element.descendants().any { it.name.namespaceURI == "http://schemas.microsoft.com/office/word/2010/wordprocessingShape" && it.name.localPart == "txbx" }) modernInline = true
                    if (element.name.namespaceURI == NativeTextBoxes.WP && element.name.localPart == "docPr") {
                        val value = element.attribute("", "id")?.toLongOrNull()
                            ?: PdfFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.DRAWING_IDENTIFIER_IS_INVALID)
                        if (value !in 0..Int.MAX_VALUE.toLong()) PdfFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.DRAWING_IDENTIFIER_IS_INVALID)
                        id = maxOf(id, value)
                    }
                    if (entry.name == "word/document.xml" && element.name.namespaceURI == NativeTextBoxes.WORD && element.name.localPart == "sectPr") {
                        val margin = element.elements().singleOrNull { it.name.localPart == "pgMar" && it.name.namespaceURI == NativeTextBoxes.WORD }
                            ?.attribute(NativeTextBoxes.WORD, "right")?.toLongOrNull()
                        rightMargin = minOf(rightMargin, if (margin != null && margin in 0..100_000) margin * 635 else 0)
                    }
                }
            }
            if (options.profile == PdfProfile.PDFA_1B) {
                val typesEntry = entries.singleOrNull { it.name == "[Content_Types].xml" }
                    ?: PdfFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.CONVERSION_PACKAGE_HAS_NO_CONTENT_TYPES)
                val types = xml.read(bytes(typesEntry))
                val defaults = types.elements().filter { it.name.localPart == "Default" }.associate {
                    (it.attribute("", "Extension") ?: "").lowercase(java.util.Locale.ROOT) to (it.attribute("", "ContentType") ?: "")
                }
                val overrides = types.elements().filter { it.name.localPart == "Override" }.associate {
                    (it.attribute("", "PartName") ?: "").removePrefix("/") to (it.attribute("", "ContentType") ?: "")
                }
                for (entry in entries.filter { !it.isDirectory }) {
                    val type = (overrides[entry.name] ?: defaults[entry.name.substringAfterLast('.').lowercase(java.util.Locale.ROOT)] ?: "").lowercase(java.util.Locale.ROOT)
                    if (entry.name.startsWith("word/media/") || type.startsWith("image/")) Pdfa1Safety.media(entry.name, bytes(entry))
                    if (entry !in wordParts && (entry.name.lowercase(java.util.Locale.ROOT).endsWith(".xml") || type == "application/xml" || type == "text/xml" || type.endsWith("+xml"))) Pdfa1Safety.validate(xml.read(bytes(entry)))
                }
                for (entry in entries.filter { it.name.endsWith(".rels") }) {
                    for (target in Pdfa1Safety.imageTargets(xml.read(bytes(entry)), entry.name)) {
                        val image = entries.singleOrNull { it.name == target }
                            ?: PdfFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.PDF_A_1B_REQUIRES_INTERNAL_OPAQUE_PNG_JPEG_IMAGE)
                        Pdfa1Safety.media(image.name, bytes(image))
                    }
                }
                if (entries.any { it.name.startsWith("word/charts/") || it.name.startsWith("word/diagrams/") || it.name.startsWith("word/embeddings/") })
                    PdfFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.PDF_A_1B_DOES_NOT_SUPPORT_CHARTS_DIAGRAMS_OR)
            }
            if (rightMargin == Long.MAX_VALUE) rightMargin = 0
            val boxes = NativeTextBoxes(xml, rightMargin, id)
            Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { file ->
                ZipOutputStream(CappedOutput(file, context)).use { output ->
                    for (entry in entries) {
                        context.checkActive()
                        output.putNextEntry(ZipEntry(entry.name))
                        if (entry in wordParts) {
                            val original = bytes(entry)
                            val tree = xml.read(original)
                            val before = boxes.normalized
                            boxes.apply(tree)
                            var changed = boxes.normalized != before
                            if (entry.name == "word/styles.xml" && options.paragraphDefaults != null) {
                                val beforeStyles = xml.write(tree)
                                ParagraphFallbacks.apply(tree, options.paragraphDefaults)
                                changed = changed || !beforeStyles.contentEquals(xml.write(tree))
                            }
                            output.write(if (changed) xml.write(tree) else original)
                        } else input.getInputStream(entry).use { PdfStreams.copy(it, output, context.limits.maxExpandedBytes, context) }
                        output.closeEntry()
                    }
                }
            }
            if (options.paragraphDefaults != null && entries.none { it.name == "word/styles.xml" }) {
                PdfFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.PARAGRAPH_COMPATIBILITY_DEFAULTS_REQUIRE_A_STYLES_PART)
            }
            return Summary(boxes.normalized, modernInline)
        }
    }

    private class CappedOutput(private val output: OutputStream, private val context: GenerationContext) : OutputStream() {
        private var written = 0L
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
            context.checkActive()
            if (length > context.limits.maxOutputBytes - written) limited()
            output.write(bytes, offset, length)
            written += length
        }
        override fun flush() = output.flush()
        override fun close() = output.close()
    }

    private fun limited(): Nothing = PdfFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.CONVERSION_PACKAGE_BYTE_OR_ENTRY_BUDGET_WAS_EXCEEDED)
}

package dev.nuaforma.docx.pdf.adapters.ooxml

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.internal.PdfFailure
import dev.nuaforma.docx.pdf.internal.PdfOptions
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
            }) PdfFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, "Conversion package has invalid archive entries")
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
                for (element in tree.descendants()) {
                    if (element.name.namespaceURI == NativeTextBoxes.WP && element.name.localPart == "inline" &&
                        element.descendants().any { it.name.namespaceURI == "http://schemas.microsoft.com/office/word/2010/wordprocessingShape" && it.name.localPart == "txbx" }) modernInline = true
                    if (element.name.namespaceURI == NativeTextBoxes.WP && element.name.localPart == "docPr") {
                        val value = element.attribute("", "id")?.toLongOrNull()
                            ?: PdfFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, "Drawing identifier is invalid")
                        if (value !in 0..Int.MAX_VALUE.toLong()) PdfFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, "Drawing identifier is invalid")
                        id = maxOf(id, value)
                    }
                    if (entry.name == "word/document.xml" && element.name.namespaceURI == NativeTextBoxes.WORD && element.name.localPart == "sectPr") {
                        val margin = element.elements().singleOrNull { it.name.localPart == "pgMar" && it.name.namespaceURI == NativeTextBoxes.WORD }
                            ?.attribute(NativeTextBoxes.WORD, "right")?.toLongOrNull()
                        rightMargin = minOf(rightMargin, if (margin != null && margin in 0..100_000) margin * 635 else 0)
                    }
                }
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
                PdfFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, "Paragraph compatibility defaults require a styles part")
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

    private fun limited(): Nothing = PdfFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "Conversion package byte or entry budget was exceeded")
}

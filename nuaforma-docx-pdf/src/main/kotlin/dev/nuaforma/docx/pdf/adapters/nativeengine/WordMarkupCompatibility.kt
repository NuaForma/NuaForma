package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.GenerationException
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlNode
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlText
import java.util.zip.ZipException

/** Select one representation for this bounded print consumer, before interpreting its text. */
internal class WordMarkupCompatibility(private val context: GenerationContext) {
    private val compatibility = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    private val understood = setOf(
        "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
        "http://schemas.openxmlformats.org/drawingml/2006/main",
        "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing",
        "http://schemas.openxmlformats.org/drawingml/2006/picture",
        "http://schemas.microsoft.com/office/word/2010/wordprocessingShape")

    fun normalize(root: XmlElement, failure: (XmlElement, String) -> NativeDocxReadException): Boolean {
        if (root.name.namespaceURI == compatibility) throw failure(root, "Invalid compatibility document root")
        var changed = false

        fun visit(parent: XmlElement, inherited: Map<String, String>) {
            context.checkActive()
            val scope = inherited + parent.namespaces
            val content = mutableListOf<XmlNode>()
            for (child in parent.children) {
                context.checkActive()
                if (child !is XmlElement) { content += child; continue }
                if (child.name.namespaceURI == compatibility) {
                    if (child.name.localPart != "AlternateContent") throw failure(child, "Misplaced compatibility branch")
                    val wrapperScope = scope + child.namespaces
                    val selected = select(child, wrapperScope, failure)
                    val selectedScope = wrapperScope + selected.namespaces
                    visit(selected, wrapperScope)
                    for (node in selected.children) {
                        if (node is XmlElement) for ((prefix, namespace) in selectedScope)
                            node.namespaces.putIfAbsent(prefix, namespace)
                        content += node
                    }
                    changed = true
                } else {
                    visit(child, scope)
                    content += child
                }
            }
            parent.children.clear()
            parent.children += content
        }

        visit(root, emptyMap())
        return changed
    }

    private fun select(wrapper: XmlElement, scope: Map<String, String>, failure: (XmlElement, String) -> NativeDocxReadException): XmlElement {
        if (wrapper.attributes.isNotEmpty() || wrapper.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
            throw failure(wrapper, "Unsupported compatibility container policy")
        var chosen: XmlElement? = null
        var fallback: XmlElement? = null
        var choices = 0
        for (branch in wrapper.elements()) {
            context.checkActive()
            if (branch.name.namespaceURI != compatibility || branch.children.filterIsInstance<XmlText>().any { it.text.isNotBlank() })
                throw failure(branch, "Invalid compatibility branch")
            when (branch.name.localPart) {
                "Choice" -> {
                    choices++
                    if (fallback != null || branch.attributes.size != 1 || branch.attributes.keys.any { it.namespaceURI.isNotEmpty() || it.localPart != "Requires" })
                        throw failure(branch, "Invalid compatibility choice requirements")
                    val requires = branch.attribute("", "Requires")?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw failure(branch, "Missing compatibility choice requirements")
                    val bindings = scope + branch.namespaces
                    val namespaces = requires.split(Regex("\\s+")).map { prefix ->
                        context.checkActive()
                        bindings[prefix]?.takeIf { it.isNotEmpty() } ?: throw failure(branch, "Unbound compatibility namespace prefix")
                    }
                    if (chosen == null && namespaces.all { it in understood }) chosen = branch
                }
                "Fallback" -> {
                    if (choices == 0 || fallback != null || branch.attributes.isNotEmpty())
                        throw failure(branch, "Invalid compatibility fallback")
                    fallback = branch
                }
                else -> throw failure(branch, "Unsupported compatibility branch")
            }
        }
        if (choices == 0) throw failure(wrapper, "Missing compatibility choice")
        return chosen ?: fallback ?: throw failure(wrapper, "No qualified compatibility representation")
    }

    companion object {

        /** Retain the normal DOCX parser's diagnostics for malformed packages/XML. Never repair them here. */
        fun prepare(bytes: ByteArray, context: GenerationContext): ByteArray {
            val archive = try { CleanupPackage(bytes, context) }
            catch (_: ZipException) { return bytes }
            catch (_: IllegalArgumentException) { return bytes }
            WordCustomXmlMetadata(context).validateSource(archive.parts)
            val source = NativeDocxSource(context)
            val selection = WordMarkupCompatibility(context)
            var changed = false
            for (part in archive.parts.keys.filter { it.startsWith("word/") && it.endsWith(".xml") }) {
                val root = try { archive.root(part) ?: continue }
                catch (error: GenerationException) {
                    if (error.diagnostics.any { it.code == DiagnosticCodes.INVALID_TEMPLATE }) return bytes
                    throw error
                }
                source.index(root, part)
                if (selection.normalize(root) { node, message -> source.current = node; source.failure(message, part) }) {
                    archive.touch(part)
                    changed = true
                }
                if (part == "word/settings.xml") WordAuthoringMetadata.validateCompatibilitySource(root) { node ->
                    source.current = node
                    source.failure("Unsupported original Word compatibility structure", part)
                }
                if (part == "word/settings.xml") WordSettingsMetadata(context).validateEditingSource(root) { node ->
                    source.current = node
                    throw source.failure("Invalid original Word editing preference", part)
                }
                if (part == "word/settings.xml") WordSettingsMetadata(context).validateDocumentVariablesSource(root) { node ->
                    source.current = node
                    throw source.failure("Invalid original document-variable storage", part)
                }
                WordThemeColors.validateSource(root) { node, message ->
                    source.current = node
                    throw source.failure(message, part)
                }
                WordSectionMetadata.validateSource(root) { node, message ->
                    source.current = node
                    throw source.failure(message, part)
                }
                NativeDocxBookmarks(context).validateSource(root, { source.current = it }) { message ->
                    throw source.failure(message, part)
                }
                WordPictureMetadata.validateSource(root) { node, message ->
                    source.current = node
                    throw source.failure(message, part)
                }
                WordRowHeights.validateSource(root) { node, message ->
                    source.current = node
                    throw source.failure(message, part)
                }
                WordTableBorders.validateSource(root) { node, message ->
                    source.current = node
                    throw source.failure(message, part)
                }
                WordTableWidths.validateSource(root) { node, message ->
                    source.current = node
                    throw source.failure(message, part)
                }
                WordLatinFontSelection.validateSource(root) { node, message ->
                    source.current = node
                    throw source.failure(message, part)
                }
            }
            return if (changed) archive.bytes() else bytes
        }

    }

}

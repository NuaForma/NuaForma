package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import javax.xml.namespace.QName

/** Removes source constructs only; the normal template evaluator and PDF writer are never called. */
internal class NativeTemplateCleanup(private val context: GenerationContext) {
    private val word = CleanupPackage.WORD
    private val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val packageRel = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val types = "http://schemas.openxmlformats.org/package/2006/content-types"

    fun clean(source: ByteArray): Pair<ByteArray, TemplateCleanupReport> {
        val pkg = CleanupPackage(source, context)
        require(pkg.root("word/document.xml")?.name == QName(word, "document")) { "Unsupported document root" }
        require(pkg.root("_rels/.rels")?.name == QName(packageRel, "Relationships")) { "Invalid package relationships" }
        require(pkg.root("[Content_Types].xml")?.name == QName(types, "Types")) { "Invalid content types" }
        repeat(256) {
            context.checkActive()
            removeMalformedOptionalXml(pkg)
            requiredStructure(pkg)
            pkg.repairReferences()
            val bytes = pkg.bytes()
            val error = try {
                val fonts = linkedMapOf<Triple<String, Boolean, Boolean>, String>()
                NativeDocxReader(context) { family, bold, italic ->
                    fonts.getOrPut(Triple(family.lowercase(java.util.Locale.ROOT), bold, italic)) { "cleanup-font-${fonts.size + 1}" }
                }.read(bytes, inspectImages = true)
                return bytes to TemplateCleanupReport(pkg.changes)
            } catch (failure: NativeDocxReadException) { failure }
            val previousChanges = pkg.changes.size
            if (!repair(pkg, error) || pkg.changes.size == previousChanges) throw error
        }
        throw GenerationException(listOf(Diagnostic(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED,
            RejectionReason.CONVERSION_XML_STRUCTURE_BUDGET_WAS_EXCEEDED)))
    }

    private fun removeMalformedOptionalXml(pkg: CleanupPackage) {
        val required = setOf("word/document.xml", "_rels/.rels", "[Content_Types].xml")
        for (part in pkg.parts.keys.filter { it !in required && (it.endsWith(".xml") || it.endsWith(".rels")) }.toList()) {
            try { pkg.root(part) }
            catch (error: GenerationException) {
                if (error.diagnostics.any { it.code in setOf(DiagnosticCodes.CANCELLED, DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) }) throw error
                if (error.diagnostics.none { it.rejectionReason == RejectionReason.CONVERSION_COPY_CONTAINS_UNSUPPORTED_OR_MALFORMED_XML }) throw error
                pkg.remove(part, "Removed an optional part containing unsupported or malformed XML")
            }
        }
    }

    private fun requiredStructure(pkg: CleanupPackage) {
        val document = requireNotNull(pkg.root("word/document.xml"))
        val body = document.elements().singleOrNull { it.name == QName(word, "body") }
            ?: throw IllegalArgumentException("Invalid document body")
        if ("word/styles.xml" !in pkg.parts) resetStyles(pkg)
        if ("word/_rels/document.xml.rels" !in pkg.parts) pkg.replace("word/_rels/document.xml.rels",
            """<Relationships xmlns="$packageRel"/>""", "Restored required document relationships")
        addRequiredRelationship(pkg, "_rels/.rels", "$rel/officeDocument", "word/document.xml", "main document")
        for (kind in listOf("styles", "settings", "numbering", "fontTable", "webSettings", "footnotes", "endnotes")) {
            if ("word/$kind.xml" in pkg.parts) addRequiredRelationship(pkg, "word/_rels/document.xml.rels", "$rel/$kind", "$kind.xml", kind)
        }
        if ("word/theme/theme1.xml" in pkg.parts) addRequiredRelationship(pkg, "word/_rels/document.xml.rels", "$rel/theme", "theme/theme1.xml", "theme")
        addStyleContentType(pkg)
        if (body.elements().none { it.name == QName(word, "sectPr") }) {
            body.children += element("sectPr")
            pkg.touch("word/document.xml")
            pkg.record(TemplateCleanupAction.RESTORE_REQUIRED_STRUCTURE, "word/document.xml", "/w:document/w:body/w:sectPr", "Restored required section geometry")
        }
        for (section in document.descendants().filter { it.name == QName(word, "sectPr") }.toList()) {
            if (section.elements().none { it.name == QName(word, "pgSz") }) {
                section.children += element("pgSz", mapOf("w" to "11906", "h" to "16838"))
                pkg.touch("word/document.xml")
                pkg.record(TemplateCleanupAction.RESTORE_REQUIRED_STRUCTURE, "word/document.xml", "/w:sectPr/w:pgSz", "Restored missing page size using A4")
            }
            if (section.elements().none { it.name == QName(word, "pgMar") }) {
                section.children += element("pgMar", mapOf("top" to "1440", "right" to "1440", "bottom" to "1440", "left" to "1440", "header" to "720", "footer" to "720", "gutter" to "0"))
                pkg.touch("word/document.xml")
                pkg.record(TemplateCleanupAction.RESTORE_REQUIRED_STRUCTURE, "word/document.xml", "/w:sectPr/w:pgMar", "Restored missing one-inch page margins")
            }
        }
    }

    private fun addRequiredRelationship(pkg: CleanupPackage, part: String, type: String, target: String, label: String) {
        val root = pkg.root(part) ?: return
        if (root.name != QName(packageRel, "Relationships") || root.elements().any {
            it.attribute("", "Target") == target && it.attribute("", "Type") == type && it.attribute("", "TargetMode") in setOf(null, "Internal") }) return
        val ids = root.elements().map { it.attribute("", "Id") }.toSet()
        val id = generateSequence(1) { it + 1 }.map { "nfCleanupRelation$it" }.first { it !in ids }
        root.children += XmlElement(QName(packageRel, "Relationship", root.name.prefix), linkedMapOf(
            QName("Id") to id, QName("Type") to type, QName("Target") to target))
        pkg.touch(part)
        pkg.record(TemplateCleanupAction.RESTORE_REQUIRED_STRUCTURE, part, null, "Restored the required $label relationship")
    }

    private fun addStyleContentType(pkg: CleanupPackage) {
        val part = "[Content_Types].xml"
        val root = requireNotNull(pkg.root(part))
        if (root.elements().any { it.attribute("", "PartName") == "/word/styles.xml" }) return
        root.children += XmlElement(QName(types, "Override", root.name.prefix), linkedMapOf(QName("PartName") to "/word/styles.xml",
            QName("ContentType") to "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"))
        pkg.touch(part)
        pkg.record(TemplateCleanupAction.RESTORE_REQUIRED_STRUCTURE, part, null, "Restored the styles content-type declaration")
    }

    private fun resetStyles(pkg: CleanupPackage) {
        pkg.replace("word/styles.xml", """<w:styles xmlns:w="$word"><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="22"/></w:rPr></w:style></w:styles>""",
            "Restored a supported Normal style using Liberation Sans 11pt; review formatting")
        for (name in pkg.parts.keys.filter { it.startsWith("word/") && it.endsWith(".xml") && it != "word/styles.xml" }) {
            val root = pkg.root(name) ?: continue
            for (parent in root.descendants().toList()) for (child in parent.elements().filter {
                it.name.namespaceURI == word && it.name.localPart in setOf("pStyle", "rStyle", "tblStyle") }) {
                pkg.unlink(name, parent, child, "/${child.name.localPart}", "Removed a reference to reset style definitions")
            }
        }
    }

    private fun repair(pkg: CleanupPackage, error: NativeDocxReadException): Boolean {
        val reason = error.sourceContext.explanation
        val malformedJpeg = error.part.matches(Regex("word/media/[A-Za-z0-9_.-]+\\.(?i:jpe?g)")) && reason.startsWith("Unsupported or malformed JPEG image")
        if ((reason == "Unsupported package part" || error.part.endsWith(".emf", true) && reason.startsWith("EMF:") || malformedJpeg) && error.part in pkg.parts) {
            pkg.remove(error.part, reason)
            return true
        }
        val root = pkg.root(error.part) ?: return false
        val path = error.sourceContext.path
        val pair = locate(root, error.sourceRoute)
        val node = pair?.second
        val explanation = node?.let { numericContext(reason, it) } ?: reason
        if (node != null && repairAttributes(pkg, error, node)) return true
        if (node?.name == QName(word, "body")) return repairBody(pkg, node, reason)
        if (node != null && pair.first != null) {
            pkg.unlink(error.part, requireNotNull(pair.first), node, path, explanation)
            return true
        }
        if (error.part == "word/styles.xml" && reason in setOf("Explicit default paragraph style is required", "Duplicate style ID", "Unsupported styles root")) {
            resetStyles(pkg)
            return true
        }
        if (error.part !in setOf("word/document.xml", "word/styles.xml", "[Content_Types].xml", "_rels/.rels", "word/_rels/document.xml.rels")) {
            if (node?.name?.namespaceURI == word && node.name.localPart in setOf("hdr", "ftr")) return false
            pkg.remove(error.part, reason)
            return true
        }
        return repairRequiredRoot(pkg, error, root)
    }

    private fun numericContext(reason: String, node: XmlElement): String {
        val fields = setOf("left", "right", "firstLine", "hanging", "top", "bottom", "header", "footer", "w", "h")
        val values = node.attributes.filter { (key, value) -> key.namespaceURI == word && key.localPart in fields &&
            value.length in 1..24 && value.matches(Regex("[-+0-9.]+")) }
        return if (values.isEmpty()) reason else (reason + "; original " + values.entries.joinToString(", ") { "${it.key.localPart}=${it.value}" }).take(256)
    }

    private fun repairRequiredRoot(pkg: CleanupPackage, error: NativeDocxReadException, root: XmlElement): Boolean {
        if (error.part == "[Content_Types].xml" && error.sourceContext.explanation == "Macros are unsupported") {
            return removeMacroDeclarations(pkg, root)
        }
        if (root.name.namespaceURI == packageRel && root.name.localPart == "Relationships") {
            val essential = root.elements().firstOrNull { node ->
                if (error.part == "_rels/.rels") node.attribute("", "Type") == "$rel/officeDocument" && node.attribute("", "Target") == "word/document.xml"
                else node.attribute("", "Type") == "$rel/styles" && node.attribute("", "Target") == "styles.xml"
            }
            val candidates = root.elements().filter { it !== essential }
            for (node in candidates) pkg.unlink(error.part, root, node, null, error.sourceContext.explanation)
            return candidates.isNotEmpty()
        }
        return false
    }

    private fun repairAttributes(pkg: CleanupPackage, error: NativeDocxReadException, node: XmlElement): Boolean {
        if (error.unsupportedAttributes.isEmpty()) return false
        val context = numericContext(error.sourceContext.explanation, node)
        for (attribute in error.unsupportedAttributes) if (node.attributes.remove(attribute) != null) {
            pkg.touch(error.part)
            pkg.record(TemplateCleanupAction.REMOVE_ATTRIBUTE, error.part, error.sourceContext.path,
                "Removed unsupported attribute ${attribute.localPart}; $context".take(256))
        }
        return true
    }

    private fun removeMacroDeclarations(pkg: CleanupPackage, root: XmlElement): Boolean {
        val part = "[Content_Types].xml"
        val declarations = root.elements().filter { it.attribute("", "ContentType")?.contains("macroEnabled", true) == true }
        for (node in declarations) {
            if (node.attribute("", "PartName") == "/word/document.xml") {
                node.attributes[QName("ContentType")] = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                pkg.touch(part)
                pkg.record(TemplateCleanupAction.RESTORE_REQUIRED_STRUCTURE, part, null, "Replaced a macro-enabled main-part declaration with ordinary DOCX")
            } else pkg.unlink(part, root, node, null, "Removed an unsupported macro-enabled content-type declaration")
        }
        return declarations.isNotEmpty()
    }

    private fun repairBody(pkg: CleanupPackage, body: XmlElement, reason: String): Boolean {
        val sections = body.elements().filter { it.name == QName(word, "sectPr") }
        val section = sections.lastOrNull() ?: return false
        for (duplicate in sections.dropLast(1)) pkg.unlink("word/document.xml", body, duplicate, "/w:body/w:sectPr", "Removed duplicate final section properties")
        if (body.elements().last() !== section) {
            body.children.remove(section)
            body.children.add(section)
            pkg.touch("word/document.xml")
            pkg.record(TemplateCleanupAction.RESTORE_REQUIRED_STRUCTURE, "word/document.xml", "/w:body/w:sectPr", reason)
            return true
        }
        return sections.size > 1
    }

    private fun locate(root: XmlElement, route: List<Pair<QName, Int>>): Pair<XmlElement?, XmlElement>? {
        if (route.isEmpty() || route.first().first != root.name) return null
        var node = root
        var parent: XmlElement? = null
        for ((name, ordinal) in route.drop(1)) {
            val next = node.elements().filter { it.name == name }.getOrNull(ordinal - 1) ?: return null
            parent = node
            node = next
        }
        return parent to node
    }

    private fun element(name: String, values: Map<String, String> = emptyMap()): XmlElement = XmlElement(QName(word, name, "w"),
        values.mapKeys { QName(word, it.key, "w") }.toMutableMap(), linkedMapOf("w" to word))
}

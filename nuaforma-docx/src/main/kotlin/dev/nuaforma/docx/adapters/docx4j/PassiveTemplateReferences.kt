package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.RejectionReason
import dev.nuaforma.docx.internal.DocxFailure
import javax.xml.namespace.QName

/** Track the settings/relationship pair while the original package is bounded and validated. */
internal class PassiveTemplateReferences {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private var templateId: String? = null
    private var relationshipId: String? = null
    private var settingsRelationships = 0
    private var styleSettingSeen = false
    private var leaf = false

    fun element(part: String, local: String, namespace: String?, depth: Int, attributes: Map<QName, String>) {
        if (depth <= 2) leaf = false
        else if (part == SETTINGS && leaf) invalid()
        if (part == SETTINGS && depth == 1 && (local != "settings" || namespace != word)) invalid()
        if (namespace != word || local !in setOf("attachedTemplate", "linkStyles")) return
        if (part != SETTINGS || depth != 2) invalid()
        leaf = true
        if (local == "attachedTemplate") attachment(attributes) else styles(attributes)
    }

    private fun attachment(attributes: Map<QName, String>) {
        val id = attributes[QName(office, "id")]
        if (templateId != null || id.isNullOrBlank() || attributes.size != 1) invalid()
        templateId = id
    }

    private fun styles(attributes: Map<QName, String>) {
        if (styleSettingSeen || attributes.keys.any { it != QName(word, "val") }) invalid()
        styleSettingSeen = true
        when (attributes[QName(word, "val")]) {
            "false", "off", "0" -> Unit
            null, "true", "on", "1" -> DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT,
                RejectionReason.EXTERNAL_TEMPLATE_STYLE_UPDATES_NOT_SUPPORTED)
            else -> invalid()
        }
    }

    fun text(part: String, depth: Int, text: String) {
        if (part == SETTINGS && leaf && depth == 2 && text.isNotBlank()) invalid()
    }

    fun relationship(part: String, id: String, type: String, mode: String?, target: String, depth: Int, attributes: Map<QName, String>): Boolean {
        if (!type.endsWith("/attachedTemplate")) return false
        if (type != "$office/attachedTemplate" || part != RELATIONSHIPS) {
            DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.LINKED_TEMPLATE_RELATIONSHIP)
        }
        val allowed = setOf("Id", "Type", "Target", "TargetMode").map { QName(it) }.toSet()
        if (relationshipId != null || mode != "External" || target.isBlank() || depth != 2 || attributes.keys != allowed) invalid()
        relationshipId = id
        return true
    }

    fun settingsRelationship(depth: Int) { if (depth == 2) settingsRelationships++ }

    fun finish(): Boolean {
        if (templateId != relationshipId || (templateId != null || styleSettingSeen) && settingsRelationships != 1) invalid()
        return templateId != null || styleSettingSeen
    }

    private fun invalid(): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.INVALID_ATTACHED_TEMPLATE_REFERENCE)

    companion object {
        const val SETTINGS = "word/settings.xml"
        const val RELATIONSHIPS = "word/_rels/settings.xml.rels"
    }
}

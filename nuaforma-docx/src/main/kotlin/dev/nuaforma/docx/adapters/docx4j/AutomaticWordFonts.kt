package dev.nuaforma.docx.adapters.docx4j

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.DeploymentFonts
import dev.nuaforma.docx.internal.BoundedStreams
import dev.nuaforma.docx.internal.DocxFailure
import dev.nuaforma.docx.internal.FontPolicy
import dev.nuaforma.docx.internal.OpenTypeMetadata
import org.docx4j.openpackaging.packages.WordprocessingMLPackage
import org.docx4j.openpackaging.parts.WordprocessingML.ObfuscatedFontPart
import org.docx4j.wml.FontRel
import org.docx4j.wml.Fonts
import java.io.ByteArrayOutputStream
import java.util.Locale

/** One request's selection, inspection and byte budget; no cross-request font state. */
internal class AutomaticWordFonts(private val document: WordprocessingMLPackage, private val request: GenerationRequest,
    private val context: GenerationContext, private val catalog: DeploymentFonts) {
    private val cache = mutableMapOf<String, ByteArray>()
    private var total = 0L
    private var faceBytes = 0L

    fun apply() {
        val policy = FontPolicy.read(request.requirements.output.options)
        if (request.resources.keys != policy.fonts.map { it.resource }.toSet()) invalid(RejectionReason.SUPPLY_EXACTLY_THE_DECLARED_FONT_RESOURCES_UNUSED_OR_MISSING)
        val explicit = policy.fonts.associateBy { identity(it.family, it.face) }
        if (explicit.size != policy.fonts.size) invalid(RejectionReason.USED_FONT_IDENTITY_CONFLICT)
        val required = UsedWordFonts(document, context).collect()
        if (!required.map { identity(it.family, it.face) }.toSet().containsAll(explicit.keys)) invalid(RejectionReason.SUPPLY_EXACTLY_THE_DECLARED_FONT_RESOURCES_UNUSED_OR_MISSING)
        val inspected = required.mapNotNull { font -> inspect(font, explicit[identity(font.family, font.face)]) }
        inspected.forEach { (font, bytes) -> context.checkActive(); WordFonts.embed(document, font, bytes) }
    }

    private fun inspect(font: UsedWordFonts.Face, override: FontPolicy.FontResource?): Pair<FontPolicy.FontResource, ByteArray>? {
        context.checkActive()
        val entry = entry(font.family)
        val existing = entry?.let { reference(it, font.face) }
        if (existing != null) {
            if (override != null) DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_OPTION, RejectionReason.REPLACING_AN_EXISTING_EMBEDDED_FONT_FACE_IS_NOT_SUPPORTED)
            validate(font, embedded(existing))
            return null
        }
        val selected = override ?: select(font)
        val source = request.resources[selected.resource] ?: catalog.source(selected.resource) ?: invalid(RejectionReason.USED_FONT_FACE_UNAVAILABLE)
        val bytes = read(selected.resource, source)
        validate(font, bytes)
        return FontPolicy.FontResource(entry?.name ?: font.family, font.face, selected.resource) to bytes
    }

    private fun entry(family: String): Fonts.Font? {
        val entries = document.mainDocumentPart.fontTablePart?.jaxbElement?.font?.filter { it.name.lowercase(Locale.ROOT) == family.lowercase(Locale.ROOT) } ?: emptyList()
        if (entries.size > 1) invalid(RejectionReason.USED_FONT_IDENTITY_CONFLICT)
        return entries.singleOrNull()
    }

    private fun reference(entry: Fonts.Font, face: String): FontRel? = when (face) {
        "bold" -> entry.embedBold
        "italic" -> entry.embedItalic
        "boldItalic" -> entry.embedBoldItalic
        else -> entry.embedRegular
    }

    private fun select(font: UsedWordFonts.Face): FontPolicy.FontResource {
        val key = identity(font.family, font.face)
        val matches = catalog.catalog.fonts.filter { identity(it.family, it.face) == key }
        val mounted = matches.filter { it.origin == FontOrigin.MOUNTED }
        if (Triple(key.first, key.second, FontOrigin.MOUNTED) in catalog.conflicts ||
            mounted.isEmpty() && Triple(key.first, key.second, FontOrigin.BUNDLED) in catalog.conflicts) invalid(RejectionReason.USED_FONT_IDENTITY_CONFLICT)
        val candidates = (if (mounted.isNotEmpty()) mounted else matches).distinctBy { it.resource }
        if (candidates.isEmpty()) {
            if (context.logging.mode == LoggingMode.DEVELOPMENT) context.logging.trace("USED_FONT_UNAVAILABLE", mapOf("family" to font.family, "face" to font.face))
            invalid(RejectionReason.USED_FONT_FACE_UNAVAILABLE)
        }
        if (candidates.size != 1) invalid(RejectionReason.USED_FONT_IDENTITY_CONFLICT)
        return FontPolicy.FontResource(font.family, font.face, candidates.single().resource ?: invalid(RejectionReason.USED_FONT_FACE_UNAVAILABLE))
    }

    private fun embedded(reference: FontRel): ByteArray {
        val table = document.mainDocumentPart.fontTablePart
        val id = reference.id ?: invalid(RejectionReason.EMBEDDED_FONT_RELATIONSHIP_IS_MISSING)
        val part = table?.relationshipsPart?.getPart(id) as? ObfuscatedFontPart ?: invalid(RejectionReason.EMBEDDED_FONT_PART_IS_MISSING)
        val bytes = read("embedded:" + part.getPartName().name, ResourceSource { part.bytes.inputStream() }).clone()
        val value = reference.fontKey ?: invalid(RejectionReason.EMBEDDED_FONT_KEY_IS_MISSING)
        val guid = "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"
        if (!Regex("(?:$guid|\\{$guid\\})").matches(value) || bytes.size < 32) invalid(RejectionReason.EMBEDDED_FONT_KEY_IS_INVALID)
        val mask = value.filter { it.isLetterOrDigit() }.chunked(2).map { it.toInt(16) }.reversed()
        repeat(32) { bytes[it] = (bytes[it].toInt() xor mask[it % 16]).toByte() }
        return bytes
    }

    private fun read(id: String, source: ResourceSource): ByteArray = cache.getOrPut(id) {
        val output = ByteArrayOutputStream()
        source.openStream().use { BoundedStreams.copy(it, output,
            minOf(16L * 1024 * 1024, minOf(64L * 1024 * 1024, context.limits.maxExpandedBytes) - total), context) }
        output.toByteArray().also { total += it.size }
    }

    private fun validate(font: UsedWordFonts.Face, bytes: ByteArray) {
        faceBytes += bytes.size
        if (faceBytes > minOf(64L * 1024 * 1024, context.limits.maxExpandedBytes))
            DocxFailure.fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, RejectionReason.DOCX_BYTE_BUDGET_WAS_EXCEEDED)
        val metadata = OpenTypeMetadata.read(bytes)
        if (metadata.families.none { it.lowercase(Locale.ROOT) == font.family.lowercase(Locale.ROOT) } || metadata.face != font.face) invalid(RejectionReason.SUPPLIED_FONT_FAMILY_OR_FACE_DOES_NOT_MATCH_ITS)
        metadata.requireEditableEmbedding()
    }

    private fun identity(family: String, face: String) = family.lowercase(Locale.ROOT) to face
    private fun invalid(reason: RejectionReason): Nothing = DocxFailure.fail(DiagnosticCodes.INVALID_REQUEST, reason)
}

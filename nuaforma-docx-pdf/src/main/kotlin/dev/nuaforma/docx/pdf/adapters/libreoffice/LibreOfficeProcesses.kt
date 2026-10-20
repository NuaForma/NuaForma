package dev.nuaforma.docx.pdf.adapters.libreoffice

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.LibreOfficeConfiguration
import dev.nuaforma.docx.pdf.internal.ConversionFiles
import dev.nuaforma.docx.pdf.internal.PdfConverter
import dev.nuaforma.docx.pdf.internal.PdfProfile
import dev.nuaforma.docx.pdf.internal.RuntimeVerification
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal class LibreOfficeProcesses(
    private val configuration: LibreOfficeConfiguration?,
    private val verification: RuntimeVerification = RuntimeVerification(),
) : PdfConverter {
    private val slots = Semaphore(configuration?.maximumParallelConversions ?: 1, true)
    private data class LauncherIdentity(val size: Long, val modified: FileTime, val key: Any?)

    override fun readiness(): CapabilityAssessment {
        val context = GenerationContext()
        return try {
            verify(context)
            CapabilityAssessment(AssessmentStatus.SUPPORTED)
        } catch (error: GenerationException) {
            if (error.diagnostics.any { it.code == DiagnosticCodes.CANCELLED || it.code == DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED }) throw error
            unavailable()
        }
        catch (_: java.io.IOException) { unavailable() }
    }

    override fun verify(context: GenerationContext) {
        context.checkActive()
        if (configuration == null) {
            context.logging.operation("PDF_RUNTIME_UNCONFIGURED", mapOf("reason" to "Configure the qualified isolated PDF launcher"))
            throw GenerationException(unavailable().diagnostics)
        }
        if (!Files.isExecutable(configuration.launcher)) {
            verification.invalidate()
            context.logging.operation("PDF_LAUNCHER_UNAVAILABLE", mapOf("reason" to "Check that the configured PDF launcher exists and is executable"))
            throw GenerationException(unavailable().diagnostics)
        }
        val identity = identity()
        verification.ensure(identity, context) {
            withSlot(context) { probe(identity, context) }
        }
    }

    private fun probe(identity: LauncherIdentity, context: GenerationContext) {
        ConversionFiles.create(context).use { files ->
            configure(files)
            val response = files.directory.resolve("version.txt")
            BoundedProcess.run(command(files, "probe", context), context, response, operationDirectory = files.directory)
            val lines = Files.readString(response).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines != listOf("LibreOffice 26.8.1.1 bf6ef2662401c7d6dce671ab4deb26ee0af3a7c1", "NuaForma PDF validator 1", "NuaForma PDF/A validator 1")) {
                context.logging.operation("PDF_RUNTIME_VERSION_MISMATCH", mapOf("reason" to "Renderer or validator identity does not match the qualified runtime"))
                throw GenerationException(unavailable().diagnostics)
            }
            if (identity != identity()) throw GenerationException(unavailable().diagnostics)
        }
    }

    private fun identity(): LauncherIdentity {
        return try {
            val attributes = Files.readAttributes(configuration!!.launcher, BasicFileAttributes::class.java)
            LauncherIdentity(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
        } catch (error: java.io.IOException) {
            verification.invalidate()
            throw error
        }
    }

    override fun convert(files: ConversionFiles, context: GenerationContext) {
        if (configuration == null || !Files.isExecutable(configuration.launcher)) {
            verification.invalidate()
            throw GenerationException(unavailable().diagnostics)
        }
        configure(files)
        val mode = if (files.profile == PdfProfile.STANDARD) "convert" else "convert-${files.profile.id}"
        withSlot(context) { BoundedProcess.run(command(files, mode, context), context, operationDirectory = files.directory) }
    }

    override fun validate(files: ConversionFiles, context: GenerationContext) {
        withSlot(context) { BoundedProcess.run(command(files, "validate", context), context, operationDirectory = files.directory) }
        if (files.profile != PdfProfile.STANDARD) {
            val response = files.directory.resolve("conformance.txt")
            withSlot(context) { BoundedProcess.run(command(files, "validate-${files.profile.id}", context), context, response, 256, files.directory) }
            context.checkActive()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(files.pdf).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    context.checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val expected = "NuaForma PDF/A valid ${files.profile.id} ${java.util.HexFormat.of().formatHex(digest.digest())}"
            if (Files.readString(response).trimEnd() != expected) throw GenerationException(listOf(Diagnostic(
                DiagnosticCodes.GENERATION_FAILED, "Generated PDF did not receive complete conformance validation for the requested profile",
            )))
        }
    }

    override fun fontFamilies(files: ConversionFiles, context: GenerationContext): Set<String> {
        if (configuration == null) throw GenerationException(unavailable().diagnostics)
        configure(files)
        val response = files.directory.resolve("font-families.txt")
        withSlot(context) { BoundedProcess.run(command(files, "fonts", context), context, response, 65536, files.directory) }
        context.checkActive()
        return Files.readString(response).lineSequence().flatMap { it.split(',').asSequence() }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    override fun fontCatalog(files: ConversionFiles, context: GenerationContext): List<AvailableFont> {
        if (configuration == null) throw GenerationException(unavailable().diagnostics)
        configure(files)
        val response = files.directory.resolve("font-catalog.txt")
        withSlot(context) { BoundedProcess.run(command(files, "catalog", context), context, response, 262144, files.directory) }
        context.checkActive()
        val fonts = linkedMapOf<Pair<String, String>, AvailableFont>()
        for (line in Files.readString(response).lineSequence().filter { it.isNotEmpty() }) {
            val columns = line.split('\t')
            if (columns.size != 3) throw GenerationException(unavailable().diagnostics)
            val weight = columns[1].toIntOrNull()
            val slant = columns[2].toIntOrNull()
            val range = Regex("\\[[0-9]{1,3} [0-9]{1,3}]")
            val variable = (weight == null && range.matches(columns[1])) || (slant == null && range.matches(columns[2]))
            if ((weight == null && !range.matches(columns[1])) || (slant == null && !range.matches(columns[2])))
                throw GenerationException(unavailable().diagnostics)
            val bold = (weight ?: 80) >= 180
            val italic = (slant ?: 0) != 0
            val face = if (variable) "variable" else if (bold && italic) "boldItalic" else if (bold) "bold" else if (italic) "italic" else "regular"
            for (family in columns[0].split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                fonts.putIfAbsent(family to face, AvailableFont(family, face, FontOrigin.BUNDLED))
                if (fonts.size > 2048) throw GenerationException(unavailable().diagnostics)
            }
        }
        return fonts.values.sortedWith(compareBy({ it.family }, { it.face }))
    }

    private fun command(files: ConversionFiles, mode: String, context: GenerationContext): List<String> = listOf(
        configuration!!.launcher.toString(), files.directory.toString(), when (mode) { "probe" -> "4096"; "fonts" -> "65536"; "catalog" -> "262144"; else -> context.limits.maxOutputBytes.toString() },
        maxOf(1L, minOf(86400L, context.limits.maxDuration.seconds + 1)).toString(), mode,
        context.limits.maxExpandedBytes.toString(),
        files.profile.version,
    )

    private fun configure(files: ConversionFiles) {
        Files.writeString(files.directory.resolve("fonts.conf"), """<?xml version="1.0"?><fontconfig><dir>/work/fonts</dir><dir>/usr/share/fonts</dir><dir>/opt/libreoffice26.8/share/fonts/truetype</dir><cachedir>/work/cache/fontconfig</cachedir><config><rescan><int>0</int></rescan></config></fontconfig>""")
        Files.writeString(files.directory.resolve("profile/registrymodifications.xcu"), """<?xml version="1.0"?><oor:items xmlns:oor="http://openoffice.org/2001/registry"><item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop></item><item oor:path="/org.openoffice.Office.Writer/Content/Update"><prop oor:name="Link" oor:op="fuse"><value>2</value></prop></item></oor:items>""")
    }

    private fun withSlot(context: GenerationContext, action: () -> Unit) {
        var acquired = false
        try {
            while (!acquired) {
                context.checkActive()
                acquired = slots.tryAcquire(50, TimeUnit.MILLISECONDS)
            }
            context.checkActive()
            action()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GenerationException(listOf(Diagnostic(DiagnosticCodes.CANCELLED, RejectionReason.PDF_CONVERSION_WAS_INTERRUPTED)))
        } finally { if (acquired) slots.release() }
    }

    private fun unavailable() = CapabilityAssessment(AssessmentStatus.UNAVAILABLE, listOf(Diagnostic(
        DiagnosticCodes.PROVIDER_UNAVAILABLE, "Configure the qualified isolated LibreOffice 26.8.1.1 runtime; DOCX output remains available",
    )))
}

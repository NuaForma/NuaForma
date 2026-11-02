package dev.nuaforma.server.internal

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.contracts.TemplateCleanupReport
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The report stays outside the reusable template; no report page or marker is inserted into Word. */
internal object TemplateCleanupArchive {

    fun write(template: Path, report: TemplateCleanupReport, output: OutputStream, context: GenerationContext) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("cleaned-template.docx"))
            Files.newInputStream(template).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    context.checkActive()
                    val length = input.read(buffer)
                    if (length < 0) break
                    zip.write(buffer, 0, length)
                }
            }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("cleanup-report.txt"))
            zip.write(reportText(report).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun reportText(report: TemplateCleanupReport): String = buildString {
        appendLine("NUAFORMA TEMPLATE CLEANUP")
        appendLine("The resulting DOCX passed the native structural source-profile check.")
        appendLine("Template instructions were not evaluated or substituted. Template syntax, supplied data and fonts still follow normal generation requirements.")
        appendLine("YOU decide whether the contents and appearance are acceptable. Open the new template in Word and review it before replacing your original.")
        appendLine("Unsupported visible objects or formatting may have been removed. Required supported defaults may have been restored. Your original was not modified.")
        appendLine("Locations describe the intermediate package when a change was made; they are not Word page numbers.")
        appendLine()
        appendLine("Changes: ${report.changes.size}")
        for ((index, change) in report.changes.withIndex()) {
            appendLine()
            appendLine("${index + 1}. ${change.action}")
            appendLine("Part: ${change.part}")
            change.location?.let { appendLine("Location: $it") }
            appendLine(change.reason)
        }
    }
}

package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DeploymentFontsTest {
    @TempDir lateinit var root: Path
    private fun font() = javaClass.getResourceAsStream("/fonts/LibreBarcode39-Regular.ttf")!!.use { it.readAllBytes() }

    @Test fun `snapshot exposes logical resources and ignores subsequent file changes`() {
        val path = Files.write(root.resolve("barcode.ttf"), font())
        val catalog = DeploymentFonts.load(root)
        val entry = catalog.catalog.fonts.first { it.family == "Libre Barcode 39" }
        assertEquals(FontOrigin.MOUNTED, entry.origin)
        assertEquals("regular", entry.face)
        assertTrue(entry.resource!!.startsWith("nf-mounted-"))
        val request = GenerationRequest(byteArrayOf().inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.DOCX, mapOf("fontMode" to "validate", "fonts" to listOf(mapOf("family" to entry.family, "resource" to entry.resource))))))
        Files.write(path, byteArrayOf(0))
        val bound = catalog.bind(request)
        assertArrayEquals(font(), bound.resources.getValue(entry.resource!!).openStream().use { it.readAllBytes() })
        val own = ResourceSource { byteArrayOf(1).inputStream() }
        val private = GenerationRequest(byteArrayOf().inputStream(), request.requirements, resources = mapOf(entry.resource!! to own))
        assertSame(own, catalog.bind(private).resources[entry.resource])
        assertEquals(1, catalog.catalog.fonts.size)
        assertTrue(DeploymentFonts.load(root).catalog.fonts.isEmpty())
    }

    @Test fun `absent empty invalid and duplicate mounts are deterministic`() {
        assertTrue(DeploymentFonts.load(root.resolve("absent")).catalog.fonts.isEmpty())
        assertTrue(DeploymentFonts.load(root).catalog.fonts.isEmpty())
        Files.write(root.resolve("a.ttf"), font())
        Files.write(root.resolve("b.ttf"), font())
        assertEquals(1, DeploymentFonts.load(root).catalog.fonts.size)
        Files.write(root.resolve("bad.otf"), byteArrayOf(1, 2, 3))
        assertEquals(1, DeploymentFonts.load(root).catalog.fonts.size)
    }

    @Test fun `conflicting files are all excluded including identical copies with both names logged`() {
        Files.write(root.resolve("a.ttf"), font())
        Files.write(root.resolve("b.ttf"), font().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        Files.write(root.resolve("c.ttf"), font())
        val snapshot = DeploymentFonts.load(root)
        assertTrue(snapshot.catalog.fonts.isEmpty())
        assertTrue(snapshot.sourcesFor(setOf("Libre Barcode 39"), emptySet()).isEmpty())
        val events = mutableListOf<GenerationLogEvent>()
        snapshot.reportFailures(GenerationLogging(LoggingMode.PRODUCTION, null) { events.add(it) })
        assertEquals(3, events.size)
        assertTrue(events[0].text.contains("a.ttf") && events[0].text.contains("b.ttf"))
        assertTrue(events[1].text.contains("a.ttf") && events[1].text.contains("b.ttf"))
        assertTrue(events[2].text.contains("c.ttf") && events[2].text.contains("b.ttf"))
        Files.delete(root.resolve("b.ttf"))
        assertEquals(1, DeploymentFonts.load(root).catalog.fonts.size)
    }

    @Test fun `directory configuration and entry and file count budgets still abort`() {
        assertThrows(IllegalArgumentException::class.java) { DeploymentFonts.load(Path.of("relative")) }
        val file = Files.write(root.resolve("file"), byteArrayOf())
        assertThrows(IllegalArgumentException::class.java) { DeploymentFonts.load(file) }
        val large = Files.createDirectory(root.resolve("large"))
        repeat(257) { Files.write(large.resolve("notice-$it.txt"), byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { DeploymentFonts.load(large) }
        val fonts = Files.createDirectory(root.resolve("fonts"))
        repeat(65) { Files.write(fonts.resolve("font-$it.ttf"), byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { DeploymentFonts.load(fonts) }
    }

    @Test fun `late metadata rejection and conflicts never publish partial aliases or resources`() {
        val valid = font()
        val other = valid.clone()
        val name = "Libre Barcode 39".toByteArray(Charsets.UTF_16BE)
        val replacement = "Other Barcode 39".toByteArray(Charsets.UTF_16BE)
        for (index in 0..other.size - name.size) {
            if (name.indices.all { other[index + it] == name[it] }) replacement.copyInto(other, index)
        }
        Files.write(root.resolve("survivor.ttf"), other)
        Files.write(root.resolve("first.ttf"), valid)
        val aliasFont = valid.clone()
        val aliasBuffer = java.nio.ByteBuffer.wrap(aliasFont).order(java.nio.ByteOrder.BIG_ENDIAN)
        val nameTable = (0 until (aliasBuffer.getShort(4).toInt() and 65535)).single {
            String(aliasFont, 12 + it * 16, 4, Charsets.US_ASCII) == "name"
        }
        val nameOffset = aliasBuffer.getInt(20 + nameTable * 16)
        val aliasRecord = (0 until (aliasBuffer.getShort(nameOffset + 2).toInt() and 65535)).single {
            aliasBuffer.getShort(nameOffset + 6 + it * 12 + 6).toInt() == 2
        }
        aliasBuffer.putShort(nameOffset + 6 + aliasRecord * 12 + 6, 16.toShort())
        Files.write(root.resolve("conflict.ttf"), aliasFont)
        val snapshot = DeploymentFonts.load(root)
        assertEquals(listOf("Other Barcode 39"), snapshot.catalog.fonts.map { it.family })
        assertTrue(snapshot.sourcesFor(setOf("Libre Barcode 39"), emptySet()).isEmpty())
        assertTrue(snapshot.sourcesFor(setOf("Regular"), emptySet()).isEmpty())
        val oldId = "nf-mounted-" + java.security.MessageDigest.getInstance("SHA-256").digest(valid).joinToString("") { "%02x".format(it) }
        val request = GenerationRequest(byteArrayOf().inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.DOCX, mapOf("fonts" to listOf(mapOf("resource" to oldId))))))
        assertTrue(snapshot.bind(request).resources.isEmpty())
        val buffer = java.nio.ByteBuffer.wrap(valid).order(java.nio.ByteOrder.BIG_ENDIAN)
        val count = buffer.getShort(4).toInt() and 65535
        val table = (0 until count).single { String(valid, 12 + it * 16, 4, Charsets.US_ASCII) == "name" }
        val offset = buffer.getInt(20 + table * 16)
        val names = buffer.getShort(offset + 2).toInt() and 65535
        buffer.putShort(offset + 6 + (names - 1) * 12 + 10, 32767.toShort())
        Files.write(root.resolve("late.ttf"), valid)
        assertEquals(listOf("Other Barcode 39"), DeploymentFonts.load(root).catalog.fonts.map { it.family })
        val isolated = Files.createDirectory(root.resolve("late-only"))
        Files.write(isolated.resolve("valid.ttf"), font())
        Files.write(isolated.resolve("late.ttf"), valid)
        assertEquals(listOf("Libre Barcode 39"), DeploymentFonts.load(isolated).catalog.fonts.map { it.family })
    }

    @Test fun `file limits recover while rejected bytes still consume aggregate budget`() {
        Files.write(root.resolve("a.ttf"), font())
        val huge = ByteArray(16 * 1024 * 1024 + 1)
        Files.write(root.resolve("b.ttf"), huge)
        assertEquals(1, DeploymentFonts.load(root).catalog.fonts.size)
        repeat(3) { Files.write(root.resolve("huge-$it.ttf"), huge) }
        assertThrows(IllegalStateException::class.java) { DeploymentFonts.load(root) }
    }

    @Test fun `exact file and aggregate byte boundaries accept duplicates but one extra byte aborts`() {
        val padded = font().copyOf(16 * 1024 * 1024)
        repeat(4) { Files.write(root.resolve("font-$it.ttf"), padded) }
        assertEquals(1, DeploymentFonts.load(root).catalog.fonts.size)
        Files.write(root.resolve("extra.ttf"), byteArrayOf(0))
        assertThrows(IllegalStateException::class.java) { DeploymentFonts.load(root) }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    fun `unreadable directory remains a global failure`() {
        val directory = Files.createDirectory(root.resolve("denied"))
        val permissions = Files.getPosixFilePermissions(directory)
        Files.setPosixFilePermissions(directory, emptySet())
        try {
            assertThrows(java.nio.file.AccessDeniedException::class.java) { DeploymentFonts.load(directory) }
        } finally { Files.setPosixFilePermissions(directory, permissions) }
    }

    @Test fun `surviving snapshot embeds exact bytes and missing rejected resource fails without output`() {
        val mounted = Files.write(root.resolve("valid.ttf"), font())
        Files.write(root.resolve("bad.ttf"), byteArrayOf())
        val snapshot = DeploymentFonts.load(root)
        val provider = DocxProvider(snapshot)
        val entry = snapshot.catalog.fonts.single()
        val parts = SyntheticDocx.parts().mapValues { (_, bytes) -> bytes.decodeToString().replace("Arial", entry.family).toByteArray() }
        val source = SyntheticDocx.zip(parts)
        fun request(resource: String) = GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.DOCX, mapOf("fontMode" to "embed", "fonts" to listOf(mapOf("family" to entry.family, "resource" to resource))))),
            mapOf("customer" to mapOf("name" to "Ada"), "reference" to "NF001"))
        Files.write(mounted, byteArrayOf())
        val output = java.io.ByteArrayOutputStream()
        provider.generate(request(entry.resource!!), output, GenerationContext())
        val embedded = SyntheticDocx.unzip(output.toByteArray())
        val key = Regex("w:fontKey=\"([^\"]+)\"").find(embedded.getValue("word/fontTable.xml").decodeToString())!!.groupValues[1].filter { it.isLetterOrDigit() }
        val mask = key.chunked(2).map { it.toInt(16).toByte() }.reversed()
        val decoded = embedded.entries.single { it.key.endsWith(".odttf") }.value.clone()
        repeat(32) { decoded[it] = (decoded[it].toInt() xor mask[it % 16].toInt()).toByte() }
        assertArrayEquals(font(), decoded)
        val rejected = java.io.ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) { provider.generate(request("rejected-font"), rejected, GenerationContext()) }
        assertEquals(RejectionReason.SUPPLY_EXACTLY_THE_DECLARED_FONT_RESOURCES_UNUSED_OR_MISSING, error.diagnostics.single().rejectionReason)
        assertEquals(0, rejected.size())
        assertTrue(DeploymentFonts.load(root).catalog.fonts.isEmpty())
    }

    @Test fun `all invalid snapshot has no resources and development reports safe reasons and bounded stacks once`() {
        Files.write(root.resolve("bad.otf"), byteArrayOf(1, 2, 3))
        Files.write(root.resolve("unsupported.woff"), ByteArray(12))
        val snapshot = DeploymentFonts.load(root)
        assertTrue(snapshot.catalog.fonts.isEmpty())
        assertTrue(snapshot.declarations(setOf("missing")).isEmpty())
        val events = mutableListOf<GenerationLogEvent>()
        val logging = GenerationLogging(LoggingMode.DEVELOPMENT, null) { events.add(it) }
        snapshot.reportFailures(GenerationLogging.NONE)
        snapshot.reportFailures(logging)
        snapshot.reportFailures(logging)
        assertEquals(4, events.size)
        assertTrue(events.any { it.text.contains("Invalid OpenType table bounds", true) })
        assertTrue(events.any { it.text.contains("standalone", true) })
        assertTrue(events.any { it.text.contains("exception=") && it.text.contains("OpenTypeMetadata") && it.text.contains("stack=") })
        assertTrue(events.all { it.text.toByteArray().size <= 3584 && !it.text.contains(root.toString()) })
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    fun `unreadable unsupported symlink and corrupt files preserve valid fonts and escape production names`() {
        Files.write(root.resolve("valid.ttf"), font())
        val unreadable = Files.write(root.resolve("unreadable.ttf"), font())
        val permissions = Files.getPosixFilePermissions(unreadable)
        Files.setPosixFilePermissions(unreadable, emptySet())
        try {
            assertFalse(Files.isReadable(unreadable), "This regression must run as an unprivileged user")
            Files.write(root.resolve("bad\nname.otf"), byteArrayOf())
            Files.write(root.resolve("unsupported.ttc"), ByteArray(12))
            Files.createSymbolicLink(root.resolve("linked.ttf"), root.resolve("valid.ttf"))
            val events = mutableListOf<GenerationLogEvent>()
            val snapshot = DeploymentFonts.load(root)
            val provider = DocxProvider(snapshot)
            assertTrue(provider.availability(GenerationContext(GenerationLogging(LoggingMode.PRODUCTION, null) { events.add(it) }))
                .supports(DocumentFormat.DOCX, DocumentFormat.DOCX))
            assertEquals(1, snapshot.catalog.fonts.size)
            assertEquals(4, events.size)
            assertTrue(events.any { it.text.contains("bad\\u000aname.otf") })
            assertTrue(events.all { it.code == "MOUNTED_FONT_REJECTED" && !it.text.contains("exception=") &&
                !it.text.contains("reason=") && !it.text.contains(root.toString()) && !it.text.contains('\n') })
        } finally { Files.setPosixFilePermissions(unreadable, permissions) }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    fun `symbolic font files and directories are rejected`() {
        val actual = Files.createDirectory(root.resolve("actual"))
        val source = Files.write(actual.resolve("barcode.ttf"), font())
        val linkedDirectory = Files.createSymbolicLink(root.resolve("linked-directory"), actual)
        assertThrows(IllegalArgumentException::class.java) { DeploymentFonts.load(linkedDirectory) }
        val files = Files.createDirectory(root.resolve("files"))
        Files.createSymbolicLink(files.resolve("barcode.ttf"), source)
        assertTrue(DeploymentFonts.load(files).catalog.fonts.isEmpty())
    }
}

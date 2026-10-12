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
    }

    @Test fun `absent empty invalid and duplicate mounts are deterministic`() {
        assertTrue(DeploymentFonts.load(root.resolve("absent")).catalog.fonts.isEmpty())
        assertTrue(DeploymentFonts.load(root).catalog.fonts.isEmpty())
        Files.write(root.resolve("a.ttf"), font())
        Files.write(root.resolve("b.ttf"), font())
        assertEquals(1, DeploymentFonts.load(root).catalog.fonts.size)
        Files.write(root.resolve("bad.otf"), byteArrayOf(1, 2, 3))
        assertThrows(GenerationException::class.java) { DeploymentFonts.load(root) }
    }

    @Test fun `conflicting families and directory budgets fail explicitly`() {
        Files.write(root.resolve("a.ttf"), font())
        Files.write(root.resolve("b.ttf"), font().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        assertThrows(IllegalArgumentException::class.java) { DeploymentFonts.load(root) }
        val large = Files.createDirectory(root.resolve("large"))
        repeat(257) { Files.write(large.resolve("notice-$it.txt"), byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { DeploymentFonts.load(large) }
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
        assertThrows(IllegalArgumentException::class.java) { DeploymentFonts.load(files) }
    }
}

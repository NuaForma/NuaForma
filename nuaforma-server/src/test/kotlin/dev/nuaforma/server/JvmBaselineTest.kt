package dev.nuaforma.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class JvmBaselineTest {
    @Test
    fun `server tests execute on Java 25`() {
        assertEquals(25, Runtime.version().feature())
    }

    @Test
    fun `production bytecode retains the split library and server baseline`() {
        val modules = System.getProperty("nuaforma.architecture.modules").split('|')
        for (module in modules) {
            var classes = 0
            for (directory in System.getProperty("nuaforma.architecture.$module").split(File.pathSeparator)) {
                val root = Path.of(directory)
                if (!Files.isDirectory(root)) continue
                Files.walk(root).use { paths ->
                    paths.filter { it.toString().endsWith(".class") }.forEach { path ->
                        DataInputStream(Files.newInputStream(path)).use { input ->
                            assertEquals(0xcafebabe.toInt(), input.readInt(), path.toString())
                            assertEquals(0, input.readUnsignedShort(), "Preview classes are not supported: $path")
                            assertEquals(if (module == "nuaforma-server") 69 else 61, input.readUnsignedShort(), path.toString())
                        }
                        classes++
                    }
                }
            }
            org.junit.jupiter.api.Assertions.assertTrue(classes > 0, "$module must be inspected")
        }
    }
}

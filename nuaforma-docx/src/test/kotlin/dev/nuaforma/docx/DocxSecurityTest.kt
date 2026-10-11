package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DocxSecurityTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `fresh Java 17 process verifies cleanup isolation limits and default content safe logs`() {
        val scratch = Files.createDirectory(directory.resolve("scratch"))
        val log = directory.resolve("probe.log")
        val locations = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.getURLs().asSequence() }.map { Path.of(it.toURI()).toString() }.toList()
        val classpath = (locations + System.getProperty("java.class.path").split(File.pathSeparator)).distinct().joinToString(File.pathSeparator)
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx192m",
            "-Djava.io.tmpdir=$scratch", "-cp", classpath, "dev.nuaforma.docx.SecurityProbe")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Security probe exceeded its test timeout")
            assertTrue(Files.size(log) < 65536, "Unexpected default log volume")
            val text = Files.readString(log)
            assertEquals(0, process.exitValue(), text)
            assertTrue(text.contains("Security probe passed"), text)
            assertFalse(text.contains(SecurityProbe.SECRET), "Default logs exposed synthetic document/data content")
            Files.list(scratch).use { assertEquals(0, it.count(), "Generation left temporary files behind") }
        } finally {
            if (process.isAlive) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)) }
        }
    }
}

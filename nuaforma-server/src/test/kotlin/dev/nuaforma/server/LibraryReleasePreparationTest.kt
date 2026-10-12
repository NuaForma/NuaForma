package dev.nuaforma.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

class LibraryReleasePreparationTest {
    private val root = Path.of(System.getProperty("nuaforma.repositoryRoot"))

    @Test
    fun `library preparation is manual protected main only and never reads publishing credentials`() {
        val file = root.resolve(".github/workflows/maven-central-prepare.yml")
        val document = Files.newInputStream(file).use { Yaml(SafeConstructor(LoaderOptions())).load<Map<*, *>>(it) }
        val events = (document["on"] ?: document[true]) as Map<*, *>
        assertEquals(setOf("workflow_dispatch"), events.keys)
        assertEquals(mapOf("contents" to "read"), document["permissions"])
        val job = (document["jobs"] as Map<*, *>)["prepare"] as Map<*, *>
        assertEquals("maven-central", job["environment"])
        assertEquals("github.repository == 'NuaForma/NuaForma' && github.ref == 'refs/heads/main'", job["if"])
        val steps = (job["steps"] as List<*>).map { it as Map<*, *> }
        steps.filter { "uses" in it }.forEach { assertTrue((it["uses"] as String).matches(Regex("[^@]+@[0-9a-f]{40}"))) }
        val signer = steps.single { it["name"] == "Sign local library artifacts only" }
        assertEquals(setOf("MAVEN_SIGNING_KEY", "MAVEN_SIGNING_PASSWORD"), (signer["env"] as Map<*, *>).keys)
        assertEquals(1, steps.count { "env" in it })
        val text = Files.readString(file)
        assertFalse(text.contains("secrets.MAVEN_CENTRAL_"))
        assertFalse(text.contains("packages: write"))
        assertFalse(text.contains("curl --upload-file"))
        val upload = steps.single { (it["uses"] as? String)?.startsWith("actions/upload-artifact@") == true }
        assertEquals("build/release-review/signatures.json\nbuild/consumer-tests/verification.json\n", (upload["with"] as Map<*, *>)["path"])
        assertTrue(text.contains("--fingerprint 74317C6CE9C61B80A2A1C0B40F736E8D598CB08B"))
    }

    @Test
    fun `Gradle publication destinations are local and no secret enters a tracked properties file`() {
        val build = Files.readString(root.resolve("build.gradle.kts"))
        assertTrue(build.contains("name = \"Staging\""))
        assertTrue(build.contains("buildDirectory.dir(\"library-repository\")"))
        assertTrue(build.contains("useInMemoryPgpKeys"))
        assertFalse(Files.readString(root.resolve("gradle.properties")).contains("signingPassword"))
        assertEquals(System.getProperty("nuaforma.expectedBuildVersion"), Files.readAllLines(root.resolve("gradle.properties")).single { it.startsWith("version=") }.removePrefix("version="))
    }
}

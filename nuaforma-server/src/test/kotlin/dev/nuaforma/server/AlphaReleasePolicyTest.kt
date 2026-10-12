package dev.nuaforma.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

class AlphaReleasePolicyTest {
    private val root = Path.of(System.getProperty("nuaforma.repositoryRoot"))

    @Test fun `publishers require main environment review and candidate evidence before credentials`() {
        for ((file, environment) in listOf("central-alpha-upload.yml" to "maven-central",
            "central-alpha-publish.yml" to "maven-central", "ghcr-alpha-publish.yml" to "ghcr-release")) {
            val path = root.resolve(".github/workflows/$file")
            val document = Files.newInputStream(path).use { Yaml(SafeConstructor(LoaderOptions())).load<Map<*, *>>(it) }
            val events = (document["on"] ?: document[true]) as Map<*, *>
            assertEquals(setOf("workflow_dispatch"), events.keys)
            assertEquals(mapOf("contents" to "read"), document["permissions"])
            val job = (document["jobs"] as Map<*, *>).values.single() as Map<*, *>
            assertEquals(environment, job["environment"])
            assertEquals("github.repository == 'NuaForma/NuaForma' && github.ref == 'refs/heads/main'", job["if"])
            val steps = (job["steps"] as List<*>).map { it as Map<*, *> }
            steps.filter { "uses" in it }.forEach { assertTrue(Regex("[^@]+@[a-f0-9]{40}").matches(it["uses"] as String)) }
            val gate = steps.indexOfFirst { it["run"].toString().contains("validate_release_gate.py") }
            assertTrue(gate >= 0)
            steps.forEachIndexed { index, step ->
                val variables = step["env"] as? Map<*, *>
                if (variables?.containsKey("REGISTRY_TOKEN") == true || variables?.values?.any { it.toString().contains("secrets.") } == true) {
                    assertTrue(index > gate)
                }
            }
            val text = Files.readString(path)
            assertFalse(Regex("(?:docker (?:tag|push)|image:) [^\\n]*:latest").containsMatchIn(text))
            assertFalse(text.contains("secrets.GITHUB_TOKEN"))
        }
    }

    @Test fun `service distribution requires extractable sources and supplemental notices`() {
        val dockerfile = Files.readString(root.resolve("deployment/pdf-runtime/Dockerfile"))
        assertTrue(dockerfile.contains("COPY SOURCES.md /usr/share/nuaforma/SOURCES.md"))
        assertTrue(dockerfile.contains("test -s /opt/nuaforma/application/third-party-materials/inventory.json"))
        assertTrue(Files.readString(root.resolve(".github/workflows/container-release.yml")).contains("python3 tools/container/prepare_application_materials.py"))
    }

    @Test fun `container publication reuses qualified archive without repeating generation tests`() {
        val publisher = Files.readString(root.resolve(".github/workflows/ghcr-alpha-publish.yml"))
        assertTrue(publisher.contains("check_image_archive.py"))
        assertTrue(publisher.contains("--check-loaded"))
        assertFalse(publisher.contains("qualify_service.py"))
        assertFalse(publisher.contains("docker build"))
    }
}

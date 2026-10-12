package dev.nuaforma.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

class ContainerPackagingTest {
    private val root = Path.of(System.getProperty("nuaforma.repositoryRoot"))
    private fun yaml(relative: String): Map<*, *> = Files.newInputStream(root.resolve(relative)).use {
        Yaml(SafeConstructor(LoaderOptions())).load(it)
    }

    @Test fun `compose preserves the required isolation and explicit contexts`() {
        val document = yaml("deployment/pdf-runtime/compose.yaml")
        val service = (document["services"] as Map<*, *>)["nuaforma"] as Map<*, *>
        assertEquals(true, service["init"])
        assertEquals(true, service["read_only"])
        assertEquals("1000:1000", service["user"])
        assertEquals(listOf("ALL"), service["cap_drop"])
        assertEquals(listOf("no-new-privileges:true", "seccomp=./seccomp.json"), service["security_opt"])
        assertEquals(128, service["pids_limit"])
        assertEquals("2g", service["mem_limit"])
        assertEquals(2, service["cpus"])
        assertEquals(listOf("/tmp:rw,size=512m,mode=1777"), service["tmpfs"])
        assertEquals(listOf("127.0.0.1:8080:8080"), service["ports"])
        assertEquals("15s", service["stop_grace_period"])
        val mounts = service["volumes"] as List<*>
        assertEquals(setOf("/opt/nuaforma/custom-fonts", "/opt/nuaforma/extensions"), mounts.map { (it as Map<*, *>)["target"] }.toSet())
        assertTrue(mounts.all { (it as Map<*, *>)["read_only"] == true && it["type"] == "bind" })
        val build = service["build"] as Map<*, *>
        assertEquals("service", build["target"])
        assertEquals(mapOf("pdfvalidation" to "../../nuaforma-docx-pdf/build/pdf-validation",
            "application" to "../../nuaforma-server/build/install/nuaforma-server"), build["additional_contexts"])
    }

    @Test fun `release preparation cannot push or distribute executable images`() {
        val workflow = yaml(".github/workflows/container-release.yml")
        // SnakeYAML's YAML 1.1 resolver treats the GitHub key 'on' as true.
        val events = (workflow["on"] ?: workflow[true]) as Map<*, *>
        assertEquals(setOf("workflow_dispatch"), events.keys)
        assertEquals(mapOf("contents" to "read"), workflow["permissions"])
        val job = (workflow["jobs"] as Map<*, *>)["qualify"] as Map<*, *>
        assertEquals("github.ref == 'refs/heads/main'", job["if"])
        val steps = job["steps"] as List<*>
        steps.map { it as Map<*, *> }.filter { "uses" in it }.forEach {
            assertTrue(Regex("[^@]+@[a-f0-9]{40}").matches(it["uses"] as String))
        }
        val uploads = steps.map { it as Map<*, *> }.filter { (it["uses"] as? String)?.startsWith("actions/upload-artifact@") == true }
        assertEquals(1, uploads.size)
        val paths = ((uploads.single()["with"] as Map<*, *>)["path"] as String).trim().lines()
        assertEquals(listOf("build/container-release/inventory/", "build/container-release/qualification/qualification.json"), paths)
        assertFalse(steps.any { (it as Map<*, *>)["run"].toString().contains("docker push") })
        assertFalse(steps.any { (it as Map<*, *>)["run"].toString().contains("docker login") })
    }

    @Test fun `build context denies incidental files and launcher keeps fixed classpath`() {
        val context = Files.readAllLines(root.resolve("deployment/pdf-runtime/.dockerignore"))
        assertEquals("**", context.first())
        assertFalse(context.contains("!**"))
        assertTrue(context.contains("!start-server.sh"))
        val launcher = Files.readString(root.resolve("deployment/pdf-runtime/start-server.sh"))
        assertTrue(launcher.contains("exec /opt/java/openjdk/bin/java"))
        assertTrue(launcher.contains("-cp '/opt/nuaforma/application/lib/*' dev.nuaforma.server.Main"))
        assertFalse(launcher.contains("eval "))
        assertFalse(launcher.contains("\r"))
    }
}

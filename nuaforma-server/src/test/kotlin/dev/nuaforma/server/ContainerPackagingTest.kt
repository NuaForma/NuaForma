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
        assertEquals(listOf("no-new-privileges:true", "seccomp=\${NUAFORMA_SECCOMP_PROFILE:-./seccomp.json}"), service["security_opt"])
        assertEquals("\${NUAFORMA_PLATFORM:-linux/amd64}", service["platform"])
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
        assertEquals(listOf("\${NUAFORMA_PLATFORM:-linux/amd64}"), build["platforms"])
        assertEquals(mapOf("pdfvalidation" to "../../nuaforma-docx-pdf/build/pdf-validation",
            "verapdfvalidation" to "../../nuaforma-docx-pdf/build/verapdf-validation",
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
        assertEquals("\${{ matrix.runner }}", job["runs-on"])
        val strategy = job["strategy"] as Map<*, *>
        assertEquals(false, strategy["fail-fast"])
        val matrix = strategy["matrix"] as Map<*, *>
        assertEquals(listOf(mapOf("architecture" to "amd64", "runner" to "ubuntu-24.04"),
            mapOf("architecture" to "arm64", "runner" to "ubuntu-24.04-arm")), matrix["include"])
        val steps = job["steps"] as List<*>
        steps.map { it as Map<*, *> }.filter { "uses" in it }.forEach {
            assertTrue(Regex("[^@]+@[a-f0-9]{40}").matches(it["uses"] as String))
        }
        val uploads = steps.map { it as Map<*, *> }.filter { (it["uses"] as? String)?.startsWith("actions/upload-artifact@") == true }
        assertEquals(1, uploads.size)
        val paths = ((uploads.single()["with"] as Map<*, *>)["path"] as String).trim().lines()
        assertEquals(listOf("build/container-release/inventory/", "build/container-release/qualification/qualification.json",
            "build/container-release/isolation/isolation.txt", "build/container-release/compatibility-isolation/isolation.txt"), paths)
        assertFalse(steps.any { (it as Map<*, *>)["run"].toString().contains("docker push") })
        assertFalse(steps.any { (it as Map<*, *>)["run"].toString().contains("docker login") })
        assertTrue(steps.any { (it as Map<*, *>)["run"].toString().contains("verify_native_platform.py") })
    }

    @Test fun `native candidates exercise both platforms without publishing`() {
        val workflow = yaml(".github/workflows/native-container.yml")
        assertEquals(mapOf("contents" to "read"), workflow["permissions"])
        val job = (workflow["jobs"] as Map<*, *>)["qualify"] as Map<*, *>
        assertEquals("\${{ matrix.runner }}", job["runs-on"])
        val matrix = ((job["strategy"] as Map<*, *>)["matrix"] as Map<*, *>)["include"] as List<*>
        assertEquals(setOf("amd64", "arm64"), matrix.map { (it as Map<*, *>)["architecture"] }.toSet())
        val steps = (job["steps"] as List<*>).map { it as Map<*, *> }
        assertFalse(steps.any { (it["uses"] as? String)?.startsWith("actions/upload-artifact@") == true })
        val commands = steps.joinToString("\n") { it["run"].toString() }
        listOf("verify_native_platform.py", "check_renderer_filter.py", "qualify_sandbox.py", "qualify_service.py").forEach {
            assertTrue(commands.contains(it), it)
        }
        listOf("docker push", "docker login", "--privileged", "seccomp=unconfined").forEach {
            assertFalse(commands.contains(it), it)
        }
    }

    @Test fun `future publisher requires both exact archives before authentication`() {
        val workflow = yaml(".github/workflows/ghcr-multiarch-publish.yml")
        val events = (workflow["on"] ?: workflow[true]) as Map<*, *>
        assertEquals(setOf("workflow_dispatch"), events.keys)
        val job = (workflow["jobs"] as Map<*, *>)["publish"] as Map<*, *>
        assertEquals("ghcr-release", job["environment"])
        assertEquals("github.repository == 'NuaForma/NuaForma' && github.ref == 'refs/heads/main'", job["if"])
        val commands = (job["steps"] as List<*>).joinToString("\n") { (it as Map<*, *>)["run"].toString() }
        assertTrue(commands.indexOf("check_multiarch_archive.py") < commands.indexOf("docker login"))
        assertTrue(commands.contains("for arch in amd64 arm64"))
        assertTrue(commands.contains("check_multiarch_index.py"))
        assertFalse(commands.contains("docker build"))
        assertTrue(commands.contains("case \"\$RELEASE_VERSION\" in *-*) exit 0 ;; esac"))
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

    @Test fun `renderer keeps namespaces without exposing procfs or caller library paths`() {
        val launcher = Files.readString(root.resolve("deployment/pdf-runtime/render-pdf.sh"))
        listOf("--unshare-user", "--unshare-pid", "--unshare-net", "--unshare-ipc", "--unshare-uts",
            "--cap-drop ALL", "--clearenv", "--seccomp 3").forEach { assertTrue(launcher.contains(it), it) }
        assertFalse(launcher.contains("--proc /proc"))
        assertFalse(launcher.contains("--ro-bind /proc"))
        assertTrue(launcher.contains("--symlink /opt/libreoffice26.8/program/soffice.bin /proc/self/exe"))
        assertTrue(launcher.contains("--symlink /dev/null /proc/version"))
        assertTrue(launcher.contains("--remount-ro /proc"))
        assertTrue(launcher.contains("--setenv LD_LIBRARY_PATH /opt/libreoffice26.8/program:/validator/java/lib:/validator/java/lib/server"))
        assertTrue(launcher.contains("set -- /opt/libreoffice26.8/program/oosplash"))
    }
}

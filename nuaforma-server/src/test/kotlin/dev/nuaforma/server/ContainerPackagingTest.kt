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

    @Test fun `build matrix preserves the required Java 17 check and covers both build runtimes`() {
        val workflow = yaml(".github/workflows/build.yml")
        val job = (workflow["jobs"] as Map<*, *>)["build"] as Map<*, *>
        assertEquals("Build (Java \${{ matrix.java }})", job["name"])
        val matrix = ((job["strategy"] as Map<*, *>)["matrix"] as Map<*, *>)["include"] as List<*>
        assertEquals(setOf("17", "25"), matrix.map { (it as Map<*, *>)["java"] }.toSet())
        val steps = (job["steps"] as List<*>).map { it as Map<*, *> }
        assertTrue(steps.any { it["if"] == "matrix.java == '17'" && it["run"].toString().contains("verifyEmbeddedConsumers") })
    }

    @Test fun `compose preserves the required isolation and explicit contexts`() {
        val document = yaml("deployment/pdf-runtime/compose.yaml")
        val service = (document["services"] as Map<*, *>)["nuaforma"] as Map<*, *>
        assertEquals(true, service["init"])
        assertEquals(true, service["read_only"])
        assertEquals("1000:1000", service["user"])
        assertEquals(listOf("ALL"), service["cap_drop"])
        assertEquals(listOf("no-new-privileges:true"), service["security_opt"])
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
        val expectedMatrix = listOf("server" to "service", "playground" to "playground").flatMap { (application, target) ->
            listOf("amd64" to "ubuntu-24.04", "arm64" to "ubuntu-24.04-arm").map { (architecture, runner) ->
                mapOf("application" to application, "target" to target, "architecture" to architecture, "runner" to runner)
            }
        }
        assertEquals(expectedMatrix, matrix["include"])
        val steps = job["steps"] as List<*>
        steps.map { it as Map<*, *> }.filter { "uses" in it }.forEach {
            assertTrue(Regex("[^@]+@[a-f0-9]{40}").matches(it["uses"] as String))
        }
        val uploads = steps.map { it as Map<*, *> }.filter { (it["uses"] as? String)?.startsWith("actions/upload-artifact@") == true }
        assertEquals(1, uploads.size)
        val paths = ((uploads.single()["with"] as Map<*, *>)["path"] as String).trim().lines()
        assertEquals(listOf("build/container-release/inventory/", "build/container-release/endpoints/sbom.json", "build/container-release/qualification/qualification.json"), paths)
        assertFalse(steps.any { (it as Map<*, *>)["run"].toString().contains("docker push") })
        assertFalse(steps.any { (it as Map<*, *>)["run"].toString().contains("docker login") })
        assertTrue(steps.any { (it as Map<*, *>)["run"].toString().contains("verify_native_platform.py") })
        assertTrue((uploads.single()["with"] as Map<*, *>)["name"].toString().contains("matrix.application"))
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
        listOf("verify_native_platform.py", "qualify_service.py", "qualify_java_runtime.py", "qualify_endpoints.py", "verify_embedded_pdf.py").forEach {
            assertTrue(commands.contains(it), it)
        }
        listOf("docker push", "docker login", "--privileged", "seccomp=", "--apparmor", "check_renderer_filter.py", "qualify_sandbox.py", "apparmor_parser").forEach {
            assertFalse(commands.contains(it), it)
        }
    }

    @Test fun `future publisher requires both exact archives before authentication`() {
        val workflow = yaml(".github/workflows/ghcr-multiarch-publish.yml")
        val events = (workflow["on"] ?: workflow[true]) as Map<*, *>
        assertEquals(setOf("workflow_dispatch"), events.keys)
        val job = (workflow["jobs"] as Map<*, *>)["publish"] as Map<*, *>
        assertEquals("ghcr-release", job["environment"])
        val dispatch = events["workflow_dispatch"] as Map<*, *>
        val inputs = dispatch["inputs"] as Map<*, *>
        val application = inputs["application"] as Map<*, *>
        assertEquals("choice", application["type"])
        assertEquals(true, application["required"])
        assertEquals("both", application["default"])
        assertEquals(listOf("both", "server", "playground"), application["options"])
        assertEquals("\${{ fromJSON(inputs.application == 'server' && '[\"server\"]' || inputs.application == 'playground' && '[\"playground\"]' || '[\"server\",\"playground\"]') }}",
            ((job["strategy"] as Map<*, *>)["matrix"] as Map<*, *>)["application"])
        assertEquals("github.repository == 'NuaForma/NuaForma' && github.ref == 'refs/heads/main'", job["if"])
        val commands = (job["steps"] as List<*>).joinToString("\n") { (it as Map<*, *>)["run"].toString() }
        assertTrue(commands.indexOf("check_multiarch_archive.py") < commands.indexOf("docker login"))
        assertTrue(commands.contains("validate_multiarch_release.py --application server"))
        assertTrue(commands.indexOf("validate_multiarch_release.py") < commands.indexOf("docker login"))
        assertTrue(commands.contains("for arch in amd64 arm64"))
        assertTrue(commands.contains("check_multiarch_index.py"))
        assertTrue(commands.contains("--companion-review"))
        assertFalse(commands.contains("docker build"))
        assertTrue(commands.contains("case \"\$RELEASE_VERSION\" in *-*) exit 0 ;; esac"))
    }

    @Test fun `build context denies incidental files and launcher keeps fixed classpath`() {
        val context = Files.readAllLines(root.resolve("deployment/pdf-runtime/.dockerignore"))
        assertEquals("**", context.first())
        assertFalse(context.contains("!**"))
        assertTrue(context.contains("!start-server.sh"))
        val launcher = Files.readString(root.resolve("deployment/pdf-runtime/start-server.sh"))
        assertTrue(launcher.contains("exec /opt/java/server/bin/java"))
        assertTrue(launcher.contains("-cp '/opt/nuaforma/application/lib/*' \"\$application_main\""))
        assertTrue(launcher.contains("server) application_main=dev.nuaforma.server.Main"))
        assertTrue(launcher.contains("playground) application_main=dev.nuaforma.playground.Main"))
        assertTrue(launcher.contains("Unsupported NuaForma application."))
        assertFalse(launcher.contains("eval "))
        assertFalse(launcher.contains("\r"))
    }

    @Test fun `native validation uses a fixed Java classpath without a nested namespace runtime`() {
        val launcher = Files.readString(root.resolve("deployment/pdf-runtime/validate-pdf.sh"))
        listOf("bwrap", "--unshare-", "--ro-bind", "--seccomp", "LD_LIBRARY_PATH").forEach { assertFalse(launcher.contains(it), it) }
        assertTrue(launcher.contains("-cp '/opt/nuaforma/pdf-validation/*:/opt/nuaforma/verapdf-validation/*'"))
        assertTrue(launcher.contains("\"-Duser.home=\$work\""))
        assertTrue(launcher.contains("\"-Djava.io.tmpdir=\$work\""))
        listOf("seccomp.json", "nuaforma.apparmor", "renderer-seccomp.c", "render-pdf.sh").forEach {
            assertFalse(Files.exists(root.resolve("deployment/pdf-runtime/$it")), it)
        }
        val dockerfile = Files.readString(root.resolve("deployment/pdf-runtime/Dockerfile"))
        assertFalse(dockerfile.contains("bubblewrap"))
        assertTrue(dockerfile.contains("dev.nuaforma.validation-boundary=\"container\""))
    }

    @Test fun `helm uses runtime defaults without a node-installed profile`() {
        val deployment = Files.readString(root.resolve("deployment/helm/nuaforma/templates/deployment.yaml"))
        assertTrue(Regex("seccompProfile:\\s+type: RuntimeDefault").containsMatchIn(deployment))
        assertFalse(deployment.contains("localhostProfile"))
        assertFalse(Files.readString(root.resolve("deployment/helm/nuaforma/values.yaml")).contains("localhostProfile"))
    }

    @Test fun `bounded validators use serial collection without suppressing warnings`() {
        val launcher = Files.readString(root.resolve("deployment/pdf-runtime/validate-pdf.sh"))
        assertEquals(2, Regex("/opt/java/openjdk/bin/java ").findAll(launcher).count())
        assertEquals(2, Regex("/opt/java/openjdk/bin/java -XX:\\+UseSerialGC -Xmx256m ").findAll(launcher).count())
        assertTrue(launcher.contains("--as=1610612736"))
        assertFalse(launcher.contains("-Xlog:off"))
        listOf("--core=0", "--fsize=16777216", "--cpu=30", "-XX:MaxDirectMemorySize=64m", "-XX:MaxMetaspaceSize=128m", "-XX:CompressedClassSpaceSize=64m", "-XX:ReservedCodeCacheSize=64m", "-XX:ActiveProcessorCount=2", "-Xss512k").forEach { assertTrue(launcher.contains(it), it) }
        assertFalse(launcher.contains("/opt/java/server"))
        val dockerfile = Files.readString(root.resolve("deployment/pdf-runtime/Dockerfile"))
        val java17Downloads = Regex("ADD --checksum=sha256:[a-f0-9]{64} https://github\\.com/adoptium/temurin17-binaries/releases/download/[^ ]+ /jre\\.tar\\.gz").findAll(dockerfile).map { it.value }.toList()
        assertEquals(2, java17Downloads.size)
        listOf("x64", "aarch64").forEach { architecture ->
            assertEquals(1, java17Downloads.count { it.contains("OpenJDK17U-jre_${architecture}_linux_hotspot_17.0.21_9.tar.gz") })
        }
        assertTrue(dockerfile.contains("FROM java17-\${TARGETARCH} AS java17-archive"))
        assertTrue(dockerfile.contains("COPY --from=java17-archive /jre.tar.gz /tmp/jre.tar.gz"))
        assertTrue(dockerfile.contains("eclipse-temurin:25-jre-noble@sha256:"))
        assertTrue(dockerfile.contains("COPY --from=server-java-runtime /opt/java/openjdk /opt/java/server/"))
    }

    @Test fun `new Central workflows require explicit versions and review before publication secrets`() {
        for ((file, jobName) in listOf("central-release-upload.yml" to "upload", "central-release-publish.yml" to "publish")) {
            val workflow = yaml(".github/workflows/$file")
            val events = (workflow["on"] ?: workflow[true]) as Map<*, *>
            assertEquals(setOf("workflow_dispatch"), events.keys)
            val inputs = (events["workflow_dispatch"] as Map<*, *>)["inputs"] as Map<*, *>
            assertEquals(true, (inputs["version"] as Map<*, *>)["required"])
            assertEquals(mapOf("contents" to "read"), workflow["permissions"])
            val job = (workflow["jobs"] as Map<*, *>)[jobName] as Map<*, *>
            assertEquals("maven-central", job["environment"])
            assertEquals("github.repository == 'NuaForma/NuaForma' && github.ref == 'refs/heads/main'", job["if"])
            val steps = (job["steps"] as List<*>).map { it as Map<*, *> }
            val review = steps.indexOfFirst { it["run"].toString().contains("validate_library_release.py") }
            val secrets = steps.indexOfFirst { (it["env"] as? Map<*, *>)?.values?.any { value -> value.toString().contains("secrets.") } == true }
            assertTrue(review >= 0 && secrets > review)
            val commands = steps.joinToString("\n") { it["run"].toString() }
            assertTrue(commands.contains("--companion-review"))
            assertFalse(commands.contains("AUTOMATIC"))
        }
    }

    @Test fun `website ownership is separate without removing real product qualification`() {
        assertFalse(Files.exists(root.resolve(".github/workflows/pages.yml")))
        assertFalse(Files.exists(root.resolve("site/index.html")))
        assertFalse(Files.exists(root.resolve("tools/site/check_site.py")))
        listOf("verify_example_kit.py", "verify_client_collections.py").forEach {
            val source = Files.readString(root.resolve("tools/site-qualification/$it"))
            assertTrue(source.contains("--website-directory"))
            assertTrue(source.contains("required=True"))
            assertTrue(source.contains("tools/container"))
        }
    }

    @Test fun `normal checks cover develop while release promotion targets main only`() {
        listOf("build.yml", "native-container.yml").forEach {
            val workflow = yaml(".github/workflows/$it")
            val events = (workflow["on"] ?: workflow[true]) as Map<*, *>
            assertEquals(listOf("main", "develop"), (events["pull_request"] as Map<*, *>)["branches"])
        }
        val workflow = yaml(".github/workflows/release-promotion.yml")
        val events = (workflow["on"] ?: workflow[true]) as Map<*, *>
        assertEquals(listOf("main"), (events["pull_request"] as Map<*, *>)["branches"])
        assertEquals(mapOf("contents" to "read"), workflow["permissions"])
        val job = (workflow["jobs"] as Map<*, *>)["check"] as Map<*, *>
        assertEquals("Release promotion", job["name"])
        val commands = (job["steps"] as List<*>).joinToString("\n") { (it as Map<*, *>)["run"].toString() }
        assertTrue(commands.contains("tools/release/check_promotion.py"))
        assertFalse(commands.contains("publish"))
    }

    @Test fun `native archive transfer requires protected review and matching sources first`() {
        val workflow = yaml(".github/workflows/container-archive.yml")
        assertEquals(mapOf("contents" to "read"), workflow["permissions"])
        val job = (workflow["jobs"] as Map<*, *>)["archive"] as Map<*, *>
        assertEquals("ghcr-release", job["environment"])
        assertEquals("github.repository == 'NuaForma/NuaForma' && github.ref == 'refs/heads/main'", job["if"])
        assertEquals(mapOf("contents" to "write"), job["permissions"])
        val commands = (job["steps"] as List<*>).joinToString("\n") { (it as Map<*, *>)["run"].toString() }
        assertTrue(commands.indexOf("check_container_source_coverage.py") < commands.indexOf("docker save"))
        assertTrue(commands.indexOf("qualify_service.py") < commands.indexOf("gh release upload"))
        listOf("docker push", "docker login", "--privileged", "unconfined", "--clobber").forEach { assertFalse(commands.contains(it), it) }
        assertTrue(commands.contains(".verification.verified"))
        assertTrue(commands.contains("--expected-version"))
    }
}

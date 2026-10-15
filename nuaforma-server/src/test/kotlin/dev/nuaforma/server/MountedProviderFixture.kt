package dev.nuaforma.server

import dev.nuaforma.contracts.DocumentProvider
import dev.nuaforma.contracts.ProviderMetadata
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/** Independent Java compilation against contracts and Kotlin only, not server/core APIs. */
internal object MountedProviderFixture {
    fun build(root: Path, minor: Int = 1, missingDependency: Boolean = false, className: String = "MountedProvider", javaRelease: Int = 17): Path {
        Files.createDirectories(root)
        val source = root.resolve("$className.java")
        Files.writeString(source, """
            package independent;
            import dev.nuaforma.contracts.*;
            import java.util.*;
            import java.io.*;
            public class $className implements DocumentProvider {
                static { System.setProperty("nuaforma.mount.initialized", "yes"); System.out.println("SYNTHETIC_EXTENSION_INITIALIZED"); }
                public $className() { ${if (missingDependency) "new MissingDependency();" else ""} }
                public ProviderCapabilities capabilities() { return new ProviderCapabilities(Set.of(DocumentFormat.DOCX), Set.of(new DocumentFormat("mount-example"))); }
                public ProviderAvailability availability() { return new ProviderAvailability(Set.of(DocumentFormat.DOCX), Set.of(new DocumentFormat("mount-example"))); }
                public CapabilityAssessment assess(GenerationRequirements requirements) { return new CapabilityAssessment(AssessmentStatus.SUPPORTED); }
                public GenerationResult generate(GenerationRequest request, OutputStream output, GenerationContext context) throws IOException, GenerationException {
                    context.checkActive(); output.write(request.getData().get("reference").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return new GenerationResult(request.getRequirements().getOutput());
                }
            }
            ${if (missingDependency) "class MissingDependency {}" else ""}
        """.trimIndent())
        val classes = Files.createDirectories(root.resolve("classes"))
        val classpath = listOf(DocumentProvider::class.java, Unit::class.java).joinToString(File.pathSeparator) {
            Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
        }
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", javaRelease.toString(), "-cp", classpath, "-d", classes.toString(), source.toString()) == 0)
        val jar = root.resolve("mounted-provider.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            fun entry(name: String, bytes: ByteArray) { output.putNextEntry(JarEntry(name)); output.write(bytes); output.closeEntry() }
            entry("independent/$className.class", Files.readAllBytes(classes.resolve("independent/$className.class")))
            entry("META-INF/services/dev.nuaforma.contracts.DocumentProvider", "independent.$className\n".toByteArray())
            entry(ProviderMetadata.resourcePath("independent.$className"), "provider.id=mounted-example\nprovider.class=independent.$className\nprovider.version=0.1.0-SNAPSHOT\ncontract.major=0\ncontract.minor=$minor\n".toByteArray())
        }
        return jar
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 1..4)
        build(Path.of(args[0]), args.getOrNull(1)?.toInt() ?: 1, args.getOrNull(2)?.toBooleanStrict() ?: false, args.getOrNull(3) ?: "MountedProvider")
    }
}

package dev.nuaforma.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import dev.nuaforma.contracts.DocumentProvider
import dev.nuaforma.core.ProviderRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchitecturePolicyTest {
    private lateinit var fixtures: JavaClasses

    @BeforeAll
    fun compileFixtures(@TempDir directory: Path) {
        // Synthetic namespace stubs exercise bytecode boundaries; no vendor library is installed or executed.
        val definitions = linkedMapOf(
            "org.docx4j.ProbeWidget" to "public class ProbeWidget { public String text() { return \"fixture\"; } }",
            "org.docx4j.ProbeException" to "public class ProbeException extends Exception {}",
            "jakarta.xml.bind.ProbeNode" to "public class ProbeNode {}",
            "unassigned.vendor.ProbeUnknown" to "public class ProbeUnknown {}",
            "dev.nuaforma.docx.ProbeProvider" to "public class ProbeProvider {}",
            "dev.nuaforma.docx.adapters.docx4j.ProbeResult" to "public class ProbeResult { public org.docx4j.ProbeWidget widget; }",
            "dev.nuaforma.core.internal.ProbeHidden" to "public class ProbeHidden {}",
            "dev.nuaforma.core.ProbeCoreApi" to "public class ProbeCoreApi {}",
            "dev.nuaforma.docx.ProbeCleanFacade" to "public class ProbeCleanFacade { private dev.nuaforma.docx.adapters.docx4j.ProbeResult state; public String render() { return \"fixture\"; } }",
            "dev.nuaforma.core.ProbeConcreteProvider" to "public class ProbeConcreteProvider { public void call() { new dev.nuaforma.docx.ProbeProvider(); } }",
            "dev.nuaforma.contracts.ProbeCycleA" to "public class ProbeCycleA { public dev.nuaforma.core.ProbeCycleB value; }",
            "dev.nuaforma.core.ProbeCycleB" to "public class ProbeCycleB { public dev.nuaforma.contracts.ProbeCycleA value; }",
            "dev.nuaforma.server.ProbeInternalPackage" to "public class ProbeInternalPackage { private dev.nuaforma.core.internal.ProbeHidden value; }",
            "dev.nuaforma.server.ProbeKotlinInternal" to "public class ProbeKotlinInternal { private dev.nuaforma.core.ProviderDiscovery value; }",
            "dev.nuaforma.docx.ProbeDirectCall" to "public class ProbeDirectCall { public void call() { new org.docx4j.ProbeWidget().text(); } }",
            "dev.nuaforma.docx.ProbeDirectSignature" to "public class ProbeDirectSignature { private org.docx4j.ProbeWidget value; }",
            "dev.nuaforma.docx.ProbeExternalInheritance" to "public class ProbeExternalInheritance extends org.docx4j.ProbeWidget {}",
            "dev.nuaforma.docx.adapters.other.ProbeWrongAdapter" to "public class ProbeWrongAdapter { private org.docx4j.ProbeWidget value; }",
            "dev.nuaforma.core.adapters.docx4j.ProbeWrongOwner" to "public class ProbeWrongOwner { private org.docx4j.ProbeWidget value; }",
            "dev.nuaforma.docx.adapters.unknown.ProbeUnassigned" to "public class ProbeUnassigned { private unassigned.vendor.ProbeUnknown value; }",
            "dev.nuaforma.docx.ProbeFieldLeak" to "public class ProbeFieldLeak { public org.docx4j.ProbeWidget value; }",
            "dev.nuaforma.docx.ProbeGenericLeak" to "public class ProbeGenericLeak { public java.util.List<? extends org.docx4j.ProbeWidget[]> value() { return null; } }",
            "dev.nuaforma.docx.ProbeConstructorLeak" to "public class ProbeConstructorLeak { public ProbeConstructorLeak(org.docx4j.ProbeWidget value) {} }",
            "dev.nuaforma.docx.ProbeBoundLeak" to "public class ProbeBoundLeak<T extends org.docx4j.ProbeWidget> {}",
            "dev.nuaforma.docx.ProbeInternalLeak" to "public class ProbeInternalLeak { public java.util.List<dev.nuaforma.docx.adapters.docx4j.ProbeResult> value() { return null; } }",
            "dev.nuaforma.docx.ProbeMethodBoundLeak" to "public class ProbeMethodBoundLeak { public <T extends org.docx4j.ProbeWidget> T value() { return null; } }",
            "dev.nuaforma.docx.ProbeParameterLeak" to "public class ProbeParameterLeak { public void render(org.docx4j.ProbeWidget value) {} }",
            "dev.nuaforma.docx.ProbeExceptionLeak" to "public class ProbeExceptionLeak { public void render() throws org.docx4j.ProbeException {} }",
            "dev.nuaforma.docx.ProbeInterfaceLeak" to "public class ProbeInterfaceLeak implements java.util.function.Supplier<org.docx4j.ProbeWidget> { public org.docx4j.ProbeWidget get() { return null; } }",
            "dev.nuaforma.core.ProbeKotlinLeak" to "public class ProbeKotlinLeak { public dev.nuaforma.core.ProviderDiscovery value() { return null; } }",
            "dev.nuaforma.core.ProbeHiddenOuter" to "class ProbeHiddenOuter { public static class Nested {} }",
            "dev.nuaforma.core.ProbeNestedLeak" to "public class ProbeNestedLeak { public ProbeHiddenOuter.Nested value; }",
            "dev.nuaforma.docx.adapters.docx4j.ProbeCorrectAdapter" to "public class ProbeCorrectAdapter { public org.docx4j.ProbeWidget create(jakarta.xml.bind.ProbeNode node) { return new org.docx4j.ProbeWidget(); } }",
            "dev.nuaforma.docx.pdf.ProbeReuse" to "public class ProbeReuse { private dev.nuaforma.docx.ProbeCleanFacade value; }",
            "dev.nuaforma.server.ProbeServerApi" to "public class ProbeServerApi { private dev.nuaforma.core.ProbeCoreApi value; }",
            "dev.nuaforma.core.ProbeOwnInternal" to "public class ProbeOwnInternal { private dev.nuaforma.core.internal.ProbeHidden value; }",
            "dev.nuaforma.docx.ProbeSibling" to "public class ProbeSibling { private dev.nuaforma.docx.ProbeProvider value; }",
        )
        val source = Files.createDirectory(directory.resolve("sources"))
        val output = Files.createDirectory(directory.resolve("classes"))
        val files = definitions.map { (name, declaration) ->
            val path = source.resolve(name.replace('.', '/') + ".java")
            Files.createDirectories(path.parent)
            Files.writeString(path, "package ${name.substringBeforeLast('.')};\n$declaration\n")
            path.toString()
        }
        val classpath = listOf(DocumentProvider::class.java, ProviderRegistry::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.joinToString(File.pathSeparator)
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        val arguments = listOf("--release", "17", "-classpath", classpath, "-d", output.toString()) + files
        assertEquals(0, compiler.run(null, null, null, *arguments.toTypedArray()))
        fixtures = ClassFileImporter().importPath(output)
        assertEquals(definitions.size + 1, fixtures.size) // Includes the deliberately hidden outer's nested class.
    }

    @ParameterizedTest
    @CsvSource(
        "direction,dev.nuaforma.core.ProbeConcreteProvider",
        "internal-access,dev.nuaforma.server.ProbeInternalPackage",
        "internal-access,dev.nuaforma.server.ProbeKotlinInternal",
        "adapters,dev.nuaforma.docx.ProbeDirectCall",
        "adapters,dev.nuaforma.docx.ProbeDirectSignature",
        "adapters,dev.nuaforma.docx.ProbeExternalInheritance",
        "adapters,dev.nuaforma.docx.adapters.other.ProbeWrongAdapter",
        "adapters,dev.nuaforma.core.adapters.docx4j.ProbeWrongOwner",
        "adapters,dev.nuaforma.docx.adapters.unknown.ProbeUnassigned",
        "api-leaks,dev.nuaforma.docx.ProbeFieldLeak",
        "api-leaks,dev.nuaforma.docx.ProbeGenericLeak",
        "api-leaks,dev.nuaforma.docx.ProbeConstructorLeak",
        "api-leaks,dev.nuaforma.docx.ProbeBoundLeak",
        "api-leaks,dev.nuaforma.docx.ProbeInternalLeak",
        "api-leaks,dev.nuaforma.docx.ProbeExternalInheritance",
        "api-leaks,dev.nuaforma.docx.ProbeMethodBoundLeak",
        "api-leaks,dev.nuaforma.docx.ProbeParameterLeak",
        "api-leaks,dev.nuaforma.docx.ProbeExceptionLeak",
        "api-leaks,dev.nuaforma.docx.ProbeInterfaceLeak",
        "api-leaks,dev.nuaforma.core.ProbeKotlinLeak",
        "api-leaks,dev.nuaforma.core.ProbeNestedLeak",
    )
    fun `each relevant violation is rejected by its actual rule`(id: String, name: String) {
        val rule = ArchitecturePolicy().rule(id, setOf(name))
        assertTrue(rule.evaluate(fixtures).hasViolation(), "$id silently accepted $name")
        assertThrows(AssertionError::class.java) { rule.check(fixtures) }
    }

    @Test
    fun `cycle detection catches a real cross component bytecode cycle`() {
        val rule = ArchitecturePolicy().rule("cycles", setOf("dev.nuaforma.contracts.ProbeCycleA", "dev.nuaforma.core.ProbeCycleB"))
        assertTrue(rule.evaluate(fixtures).hasViolation())
        assertThrows(AssertionError::class.java) { rule.check(fixtures) }
    }

    @Test
    fun `legitimate adapters facade reuse own internals and public engine access are allowed`() {
        val permitted = setOf(
            "dev.nuaforma.docx.adapters.docx4j.ProbeCorrectAdapter", "dev.nuaforma.docx.ProbeCleanFacade",
            "dev.nuaforma.docx.pdf.ProbeReuse", "dev.nuaforma.server.ProbeServerApi",
            "dev.nuaforma.core.ProbeOwnInternal", "dev.nuaforma.docx.ProbeSibling",
        )
        ArchitecturePolicy.RULES.forEach { ArchitecturePolicy().rule(it, permitted).check(fixtures) }
    }
}

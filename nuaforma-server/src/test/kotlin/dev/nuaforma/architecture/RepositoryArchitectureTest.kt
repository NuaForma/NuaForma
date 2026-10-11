package dev.nuaforma.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class RepositoryArchitectureTest {
    @Test
    fun `all production modules satisfy the shared architecture policy`() {
        val policy = ArchitecturePolicy()
        val modules = requireNotNull(System.getProperty("nuaforma.architecture.modules"))
            .split('|').toSet()
        assertEquals(policy.components.map { it.module }.toSet(), modules)
        val roots = mutableListOf<Path>()
        for (module in modules.sorted()) {
            val paths = requireNotNull(System.getProperty("nuaforma.architecture.$module"))
                .split(File.pathSeparator).map { Path.of(it) }
            assertTrue(paths.isNotEmpty())
            for (path in paths.filter { Files.exists(it) }) {
                val imported = ClassFileImporter().importPath(path)
                for (type in imported) assertEquals(module, policy.component(type)?.module, "${type.name} is in the wrong module/package")
                roots.add(path)
            }
        }
        val production = ClassFileImporter().importPaths(roots)
        assertFalse(production.isEmpty(), "Architecture import must not silently be empty")
        assertTrue(production.any { it.name == "dev.nuaforma.contracts.DocumentProvider" })
        assertTrue(production.any { it.name == "dev.nuaforma.core.ProviderRegistry" })
        assertFalse(production.any { it.name == "dev.nuaforma.core.DiscoveryTest" || it.name == javaClass.name }, "Import only main outputs, never tests/fixtures")
        ArchitecturePolicy.RULES.forEach { policy.rule(it).check(production) }
    }
}

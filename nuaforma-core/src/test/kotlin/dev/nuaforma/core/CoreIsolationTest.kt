package dev.nuaforma.core

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreIsolationTest {
    private fun rule() = classes().should().onlyDependOnClassesThat().resideInAnyPackage(
        "dev.nuaforma.core..", "dev.nuaforma.contracts..", "java..", "kotlin..", "org.jetbrains.annotations..",
    )

    @Test
    fun `core knows contracts and platform types but no concrete format providers`() {
        val imported = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("dev.nuaforma.core")
        assertFalse(imported.isEmpty())
        rule().check(imported)
    }

    @Test
    fun `isolation check catches an external dependency`() {
        assertThrows(AssertionError::class.java) { rule().check(ClassFileImporter().importClasses(LeakingFixture::class.java)) }
    }

    private class LeakingFixture(val dependency: org.junit.jupiter.api.Test?)
}

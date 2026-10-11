package dev.nuaforma.contracts

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ContractIsolationTest {
    private fun rule() = classes().should().onlyDependOnClassesThat().resideInAnyPackage(
        "dev.nuaforma.contracts..", "java..", "kotlin..", "org.jetbrains.annotations..",
    )

    @Test
    fun `contracts depend only on themselves and platform types`() {
        val imported = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.nuaforma.contracts")
        assertFalse(imported.isEmpty())
        rule().check(imported)
    }

    @Test
    fun `isolation check rejects a test framework leak`() {
        assertThrows(AssertionError::class.java) {
            rule().check(ClassFileImporter().importClasses(LeakingFixture::class.java))
        }
    }

    private class LeakingFixture(val frameworkType: org.junit.jupiter.api.Test?)
}

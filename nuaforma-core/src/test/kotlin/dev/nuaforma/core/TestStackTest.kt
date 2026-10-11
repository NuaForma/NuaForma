package dev.nuaforma.core

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TestStackTest {
    @Test
    fun `MockK runs under JUnit Jupiter`() {
        val probe = mockk<TestProbe>()
        every { probe.value() } returns "ready"

        assertEquals("ready", probe.value())
        verify(exactly = 1) { probe.value() }
    }

    @Test
    fun `ArchUnit evaluates bytecode and detects violations`() {
        val imported = ClassFileImporter().importClasses(TestProbe::class.java)
        classes().should().resideInAPackage("dev.nuaforma.core").check(imported)
        assertThrows(AssertionError::class.java) {
            classes().should().resideInAPackage("dev.nuaforma.contracts").check(imported)
        }
    }
}

interface TestProbe {
    fun value(): String
}

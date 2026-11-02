package dev.nuaforma.contracts

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TemplateCleanupContractTest {

    @Test
    fun `new optional cleanup symbols require the new minor while old providers remain compatible`() {
        assertTrue(ContractVersion.CURRENT.accepts(ContractVersion(0, 8)))
        assertFalse(ContractVersion(0, 8).accepts(ContractVersion.CURRENT))
        val original = mutableListOf(TemplateCleanupChange(TemplateCleanupAction.REMOVE_PART, "docProps/thumbnail.jpeg", null, "Removed unsupported part"))
        val report = TemplateCleanupReport(original)
        original.clear()
        assertEquals(1, report.changes.size)
        assertThrows(UnsupportedOperationException::class.java) { (report.changes as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) { TemplateCleanupChange(TemplateCleanupAction.REMOVE_PART, "word/forged\nline", null, "Unsafe") }
    }
}

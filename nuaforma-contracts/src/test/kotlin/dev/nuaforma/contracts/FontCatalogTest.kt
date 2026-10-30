package dev.nuaforma.contracts

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FontCatalogTest {
    @Test fun `catalog is an immutable bounded deployment metadata snapshot`() {
        val original = mutableListOf(AvailableFont("Example", "regular", FontOrigin.MOUNTED, "logical-font"))
        val snapshot = FontCatalog(original)
        original.clear()
        assertEquals(1, snapshot.fonts.size)
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.fonts as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) { AvailableFont("Private\n", "regular", FontOrigin.MOUNTED) }
        assertThrows(IllegalArgumentException::class.java) { AvailableFont("Example", "regular", FontOrigin.MOUNTED, "../font") }
        assertThrows(IllegalArgumentException::class.java) { FontCatalog(List(2049) { AvailableFont("Font-$it", "regular", FontOrigin.BUNDLED) }) }
        assertThrows(IllegalArgumentException::class.java) { FontCatalog(listOf(snapshot.fonts.single(), snapshot.fonts.single())) }
        assertEquals(ContractVersion(0, 7), ContractVersion.CURRENT)
        assertTrue(ContractVersion.CURRENT.accepts(ContractVersion(0, 1)))
        assertTrue(ContractVersion.CURRENT.accepts(ContractVersion(0, 2)))
        assertTrue(ContractVersion.CURRENT.accepts(ContractVersion(0, 3)))
        assertTrue(ContractVersion.CURRENT.accepts(ContractVersion(0, 4)))
        assertTrue(ContractVersion.CURRENT.accepts(ContractVersion(0, 5)))
        assertFalse(ContractVersion(0, 5).accepts(ContractVersion.CURRENT))
        assertFalse(ContractVersion(0, 4).accepts(ContractVersion.CURRENT))
        assertFalse(ContractVersion(0, 3).accepts(ContractVersion.CURRENT))
        assertFalse(ContractVersion(0, 2).accepts(ContractVersion.CURRENT))
    }
}

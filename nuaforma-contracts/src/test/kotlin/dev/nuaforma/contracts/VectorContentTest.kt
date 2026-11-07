package dev.nuaforma.contracts

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VectorContentTest {

    @Test
    fun `additive vector contract accepts older provider minima and rejects older vector hosts`() {
        assertEquals(ContractVersion(0, 10), ContractVersion.CURRENT)
        assertTrue(ContractVersion.CURRENT.accepts(ContractVersion(0, 9)))
        assertFalse(ContractVersion(0, 9).accepts(ContractVersion.CURRENT))
    }


    @Test
    fun `vector snapshots paths and points and exposes immutable collections`() {
        val points = mutableListOf(VectorPoint(100, 100), VectorPoint(900, 900))
        val path = VectorPath(points)
        val paths = mutableListOf(path)
        val vector = ContentResult.Vector(paths)
        points.clear()
        paths.clear()
        assertEquals(2, vector.paths.single().points.size)
        assertEquals(70, vector.strokeWidth)
        assertThrows(UnsupportedOperationException::class.java) { (vector.paths as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (path.points as MutableList).clear() }
    }

    @Test
    fun `path geometry rejects invalid and degenerate segments`() {
        assertThrows(IllegalArgumentException::class.java) { VectorPoint(-1, 10) }
        assertThrows(IllegalArgumentException::class.java) { VectorPoint(10, 1001) }
        val a = VectorPoint(100, 100)
        val b = VectorPoint(900, 900)
        assertThrows(IllegalArgumentException::class.java) { VectorPath(listOf(a)) }
        assertThrows(IllegalArgumentException::class.java) { VectorPath(listOf(a, a)) }
        assertThrows(IllegalArgumentException::class.java) { VectorPath(listOf(a, b), true) }
        assertThrows(IllegalArgumentException::class.java) { VectorPath(listOf(a, b, a), true) }
        assertThrows(IllegalArgumentException::class.java) { VectorPath(List(17) { if (it % 2 == 0) a else b }) }
    }

    @Test
    fun `vector bounds include the entire stroke and cumulative geometry budget`() {
        val path = VectorPath(listOf(VectorPoint(50, 50), VectorPoint(950, 950)))
        assertEquals(100, ContentResult.Vector(listOf(path), 100).strokeWidth)
        for (width in listOf(9, 101)) assertThrows(IllegalArgumentException::class.java) {
            ContentResult.Vector(listOf(path), width)
        }
        assertThrows(IllegalArgumentException::class.java) { ContentResult.Vector(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ContentResult.Vector(List(9) { path }) }
        val long = VectorPath(List(16) { VectorPoint(100 + it * 10, 100) })
        assertThrows(IllegalArgumentException::class.java) { ContentResult.Vector(List(5) { long }) }
        assertThrows(IllegalArgumentException::class.java) {
            ContentResult.Vector(listOf(VectorPath(listOf(VectorPoint(0, 0), VectorPoint(500, 500)))))
        }
    }
}

package dev.nuaforma.contracts

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContentMetadataTest {
    @Test
    fun `source media metadata is additive bounded and immutable without changing previous constructors`() {
        val previous = ContentPipeDefinition("company", "Custom structured content")
        assertTrue(previous.contentTypes.isEmpty())
        val types = linkedSetOf("image/png", "image/jpeg")
        val image = ContentPipeDefinition("image", "Static image", emptyList(), emptyList(), "Null skips", types)
        types.clear()
        assertEquals(setOf("image/png", "image/jpeg"), image.contentTypes)
        assertThrows(UnsupportedOperationException::class.java) { (image.contentTypes as MutableSet).clear() }
        assertThrows(IllegalArgumentException::class.java) {
            ContentPipeDefinition("image", "Static image", emptyList(), emptyList(), "Null skips", setOf("image/png;private=value"))
        }
    }
}

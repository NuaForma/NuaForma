package dev.nuaforma.docx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JvmBaselineTest {
    @Test
    fun `tests execute on Java 17`() {
        assertEquals(17, Runtime.version().feature())
    }
}

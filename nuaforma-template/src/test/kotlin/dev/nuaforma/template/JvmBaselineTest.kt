package dev.nuaforma.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JvmBaselineTest {
    @Test
    fun `library tests execute on Java 17`() {
        assertEquals(17, Runtime.version().feature())
    }
}

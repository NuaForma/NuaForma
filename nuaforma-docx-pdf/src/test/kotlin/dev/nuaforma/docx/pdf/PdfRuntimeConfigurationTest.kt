package dev.nuaforma.docx.pdf

import dev.nuaforma.docx.pdf.internal.PdfRuntimeConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Properties

class PdfRuntimeConfigurationTest {
    @Test
    fun `parallelism is bounded trusted host configuration with unchanged default`() {
        val properties = Properties()
        assertNull(PdfRuntimeConfiguration.read(properties))
        properties.setProperty("nuaforma.pdf.launcher", "/synthetic/approved-launcher")
        assertEquals(1, PdfRuntimeConfiguration.read(properties)!!.maximumParallelConversions)
        for (value in 1..8) {
            properties.setProperty("nuaforma.pdf.parallelism", value.toString())
            assertEquals(value, PdfRuntimeConfiguration.read(properties)!!.maximumParallelConversions)
        }
        for (value in listOf("0", "9", "-1", "", "2.0", "NaN", "999999999999999999999", "secret-setting")) {
            properties.setProperty("nuaforma.pdf.parallelism", value)
            val error = assertThrows(IllegalArgumentException::class.java) { PdfRuntimeConfiguration.read(properties) }
            if (value == "secret-setting") assertFalse(error.message!!.contains(value))
        }
    }
}

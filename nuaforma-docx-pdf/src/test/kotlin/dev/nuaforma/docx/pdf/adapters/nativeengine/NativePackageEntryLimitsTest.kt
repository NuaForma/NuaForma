package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.PdfFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NativePackageEntryLimitsTest {

    private fun parts(count: Int) = PdfFixture.parts().apply {
        require(count >= size)
        repeat(count - size) { put("[trash]/%04x.dat".format(it), byteArrayOf(0, -1, 42)) }
    }

    private fun read(source: ByteArray, limits: GenerationLimits = GenerationLimits()) =
        NativeDocxReader(GenerationContext(limits)) { _, _, _ -> "fixture" }.read(source)

    private fun cleanup(source: ByteArray, limits: GenerationLimits = GenerationLimits()) =
        CleanupPackage(source, GenerationContext(limits))

    private fun limited(action: () -> Unit) {
        val failure = assertThrows(GenerationException::class.java, action)
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, failure.diagnostics.single().code)
    }

    @ParameterizedTest
    @ValueSource(ints = [64, 65, 255, 256])
    fun `both package readers admit the approved member boundary`(count: Int) {
        val source = PdfFixture.zip(parts(count))
        val original = source.copyOf()
        assertFalse(read(source).blocks.isEmpty())
        assertEquals(count, cleanup(source).parts.size)
        assertArrayEquals(original, source)
    }

    @Test
    fun `hard member ceiling and lower caller ceilings remain authoritative`() {
        for ((count, caller) in listOf(257 to 10_000, 257 to 512, 65 to 64, 256 to 255)) {
            val source = PdfFixture.zip(parts(count))
            val limits = GenerationLimits(maxArchiveEntries = caller)
            limited { read(source, limits) }
            limited { cleanup(source, limits) }
        }
        val source = PdfFixture.zip(parts(65))
        assertFalse(read(source, GenerationLimits(maxArchiveEntries = 65)).blocks.isEmpty())
        assertEquals(65, cleanup(source, GenerationLimits(maxArchiveEntries = 65)).parts.size)
    }

    @Test
    fun `individual member byte ceiling is unchanged`() {
        val exact = PdfFixture.parts().apply { put("[trash]/0000.dat", ByteArray(4_194_304)) }
        val source = PdfFixture.zip(exact)
        assertFalse(read(source).blocks.isEmpty())
        assertEquals(4_194_304, cleanup(source).parts.getValue("[trash]/0000.dat").size)
        val oversized = PdfFixture.zip(exact.apply { put("[trash]/0000.dat", ByteArray(4_194_305)) })
        limited { read(oversized) }
        limited { cleanup(oversized) }
    }

    @Test
    fun `expanded package and compressed input byte ceilings are unchanged`() {
        val exact = PdfFixture.parts().apply {
            val originalBytes = values.sumOf { it.size }
            put("[trash]/0000.dat", ByteArray(4_194_304))
            put("[trash]/0001.dat", ByteArray(8_388_608 - originalBytes - 4_194_304))
        }
        val source = PdfFixture.zip(exact)
        assertFalse(read(source).blocks.isEmpty())
        assertEquals(8_388_608, cleanup(source).parts.values.sumOf { it.size })
        val oversized = PdfFixture.zip(exact.apply {
            put("[trash]/0001.dat", getValue("[trash]/0001.dat").copyOf(getValue("[trash]/0001.dat").size + 1))
        })
        limited { read(oversized) }
        limited { cleanup(oversized) }
        val tooMuchInput = ByteArray(8_388_609)
        limited { read(tooMuchInput) }
        limited { cleanup(tooMuchInput) }
        val inputLimit = GenerationLimits(maxTemplateBytes = source.size.toLong() - 1)
        limited { read(source, inputLimit) }
        limited { cleanup(source, inputLimit) }
        val expandedLimit = GenerationLimits(maxExpandedBytes = 8_388_607)
        limited { read(source, expandedLimit) }
        limited { cleanup(source, expandedLimit) }
    }
}

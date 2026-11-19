package dev.nuaforma.docx.pdf

import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.security.MessageDigest

class NativeMarginTabsTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `right aligned fields follow the Word paragraph and text area boundaries`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        val markers = Regex("P00_PREFIX|123,T00|P99_NEXT")
        for (variant in listOf("right", "indent-only")) {
            val folder = "/tab-margin/$variant/$paper/"
            val source = support.bytes(folder + "source.docx")
            val reference = support.bytes(folder + "word-reference.pdf")
            val receipt = support.bytes(folder + "word-reference.pdf.provenance.json").decodeToString()
            assertTrue(receipt.contains(hash(source)))
            assertTrue(receipt.contains(hash(reference)))
            Loader.loadPDF(reference).use { word ->
                Loader.loadPDF(support.generate(source, "standard")).use { native ->
                    assertEquals(1, word.numberOfPages)
                    assertEquals(1, native.numberOfPages)
                    val expected = support.positions(word, markers)
                    val actual = support.positions(native, markers)
                    assertEquals(3, expected.size)
                    assertEquals(expected.keys, actual.keys)
                    for ((marker, position) in expected) {
                        assertEquals(position.x, actual.getValue(marker).x, .21f, "$variant/$paper/$marker X")
                        assertEquals(position.y, actual.getValue(marker).y, .21f, "$variant/$paper/$marker Y")
                    }
                }
            }
        }
    }

    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

}

package dev.nuaforma.docx.pdf.ownengine

import org.junit.jupiter.api.Test

/** Independent frozen Word exports and reviewed new goldens; never rewritten by tests. */
class CheckboxWordOracleTest {

    @Test
    fun `checkbox variants and inherited formatting retain the reviewed Word layout`() {
        WordLayoutOracle.qualify("checkbox", "variants", 1, Regex("(Default|Unboxed|Cross|Comparison):"))
    }

    @Test
    fun `A4 report preserves checked table rows and recurring page bands against Word`() {
        WordLayoutOracle.qualify("checkbox", "report-a4", 3, Regex("(W[0-9]{2})"))
    }

    @Test
    fun `Letter report preserves checked table rows and recurring page bands against Word`() {
        WordLayoutOracle.qualify("checkbox", "report-letter", 3, Regex("(W[0-9]{2})"))
    }
}

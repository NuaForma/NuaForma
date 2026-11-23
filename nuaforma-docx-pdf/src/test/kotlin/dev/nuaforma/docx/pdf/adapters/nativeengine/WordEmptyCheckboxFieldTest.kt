package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.IdentityHashMap

class WordEmptyCheckboxFieldTest {
    private val decode = ConversionXml(GenerationContext())

    @Test
    fun `an empty checkbox without metadata leaves the next field and surrounding runs intact`() {
        val ghost = checkbox()
        val metadata = "<w:ffData><w:checkBox><w:sizeAuto/><w:checked w:val=\"1\"/></w:checkBox></w:ffData>"
        val input = read("<w:r><w:t>Before</w:t></w:r>$ghost${checkbox(metadata)}<w:r><w:t>After</w:t></w:r>")
        val forms = IdentityHashMap<XmlElement, NativeDocxFields.Form>()
        NativeDocxFields.normalize(input, ::reject, forms)
        val kept = input.elements().single().elements()
        assertEquals(listOf("r", "fldSimple", "r"), kept.map { it.name.localPart })
        assertSame(kept[1], forms.keys.single())
        assertEquals("checkBox", forms.values.single().data.elements().single().name.localPart)
    }

    @Test
    fun `unbalanced missing data fields and unqualified caches still reject`() {
        for (code in listOf(checkbox().replace(control("end"), ""),
            checkbox().replace(control("separate"), ""),
            checkbox(cache = "<w:r><w:t>Cache</w:t></w:r>"),
            checkbox().replace("FORMCHECKBOX", "FORMTEXT"))) {
            assertThrows(IllegalArgumentException::class.java) { NativeDocxFields.normalize(read(code), ::reject) }
        }
    }

    private fun read(content: String) = decode.read(
        """<w:body xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p>$content</w:p></w:body>""".toByteArray()
    )

    private fun control(kind: String, content: String = "") =
        """<w:r><w:fldChar w:fldCharType="$kind">$content</w:fldChar></w:r>"""

    private fun checkbox(data: String = "", cache: String = "") = control("begin", data) +
        "<w:r><w:instrText>FORMCHECKBOX</w:instrText></w:r>" + control("separate") + cache + control("end")

    private fun reject(message: String): Nothing = throw IllegalArgumentException(message)
}

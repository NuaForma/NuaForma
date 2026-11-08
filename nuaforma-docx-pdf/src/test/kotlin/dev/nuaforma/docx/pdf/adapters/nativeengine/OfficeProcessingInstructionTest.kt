package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OfficeProcessingInstructionTest {
    private val root = "<ct:contentTypeSchema xmlns:ct=\"http://schemas.microsoft.com/office/2006/metadata/contentType\"/>"

    @Test
    fun `empty Office preamble is accepted only once before a custom item root`() {
        for (part in listOf("customXml/item1.xml", "customXml/item99.xml")) {
            val xml = ConversionXml(GenerationContext())
            assertEquals("contentTypeSchema", xml.readPart(("<?mso-contentType ?>" + root).toByteArray(), part).name.localPart)
        }
        for (part in listOf("word/document.xml", "customXml/itemProps1.xml", "customXml/item0.xml", "customXml/item100.xml", "customXML/item1.xml")) {
            val failure = assertThrows(GenerationException::class.java) {
                ConversionXml(GenerationContext()).readPart(("<?mso-contentType ?>" + root).toByteArray(), part)
            }
            assertEquals(part, failure.diagnostics.single().part)
        }
        assertThrows(GenerationException::class.java) { ConversionXml(GenerationContext()).read(("<?mso-contentType ?>" + root).toByteArray()) }
    }

    @Test
    fun `unknown payload duplicate placement and stylesheet instructions remain rejected`() {
        for (source in listOf("<?mso-contentType unsafe?>$root", "<?mso-contentType ?><?mso-contentType ?>$root",
            "$root<?mso-contentType ?>", "<root><?mso-contentType ?></root>", "<?xml-stylesheet href=\"file:///NEVER_FETCHED\"?>$root",
            "<?other ?>$root", "<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///NEVER_FETCHED'>]><root>&x;</root>")) {
            val error = assertThrows(GenerationException::class.java) { ConversionXml(GenerationContext()).readPart(source.toByteArray(), "customXml/item1.xml") }
            assertEquals(RejectionReason.CONVERSION_COPY_CONTAINS_UNSUPPORTED_OR_MALFORMED_XML, error.diagnostics.single().rejectionReason)
        }
    }

    @Test
    fun `metadata admission retains byte budgets and cancellation`() {
        assertThrows(GenerationException::class.java) {
            ConversionXml(GenerationContext(cancellation = CancellationToken { true })).readPart(root.toByteArray(), "customXml/item1.xml")
        }
        val error = assertThrows(GenerationException::class.java) {
            ConversionXml(GenerationContext(GenerationLimits(maxExpandedBytes = 8))).readPart(root.toByteArray(), "customXml/item1.xml")
        }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, error.diagnostics.single().code)
    }

}

package dev.nuaforma.server

import dev.nuaforma.contracts.*
import dev.nuaforma.server.internal.ApiErrors
import dev.nuaforma.server.internal.ApiDocumentation
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RejectionDiagnosticsTest {
    @Test
    fun `expression excerpts and document context are development only bounded and secret aware`() {
        val context = TemplateErrorContext("{trim(name,)}", 11, 6)
        val diagnostic = Diagnostic(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.UNEXPECTED_EXPRESSION_TOKEN,
            location = "template:17", part = "word/document.xml").withTemplateContext(context).withDocumentLocation("paragraph:4")
        for (mode in LoggingMode.entries) {
            val events = mutableListOf<GenerationLogEvent>()
            val response = ApiErrors.diagnostics(listOf(diagnostic), mode).single()
            ApiErrors.logRejections(GenerationLogging(mode, "syntax-example", events::add), listOf(diagnostic))
            if (mode == LoggingMode.DEVELOPMENT) {
                assertEquals("{trim(name,)}", response["expression"])
                assertEquals("11", response["expressionPosition"])
                assertEquals("paragraph:4", response["documentLocation"])
                assertTrue(events.any { it.development && it.text.contains("trim(name,)") })
                val schema = OpenApiSchemaChecks(ObjectMapper().readTree(ApiDocumentation().jsonText))
                assertTrue(schema.matches("Diagnostic", ObjectMapper().valueToTree<JsonNode>(response)))
            } else {
                assertFalse(response.containsKey("expression"))
                assertFalse(response.containsKey("documentLocation"))
                assertFalse(events.joinToString { it.text }.contains("trim(name,)"))
            }
            assertTrue(events.any { it.code == "GENERATION_REJECTED" && it.text.contains("UNEXPECTED_EXPRESSION_TOKEN") })
        }
        val secret = diagnostic.withTemplateContext(TemplateErrorContext("{password ?? \"PRIVATE_VALUE\" +}", 27))
        val events = mutableListOf<GenerationLogEvent>()
        val response = ApiErrors.diagnostics(listOf(secret), LoggingMode.DEVELOPMENT).single()
        ApiErrors.logRejections(GenerationLogging(LoggingMode.DEVELOPMENT, "redacted", events::add), listOf(secret))
        assertEquals("[redacted expression]", response["expression"])
        assertFalse(response.containsKey("expressionPosition"))
        assertFalse(events.joinToString { it.text }.contains("PRIVATE_VALUE"))
        val freeText = Diagnostic(DiagnosticCodes.INVALID_TEMPLATE, "Untrusted extension").withTemplateContext(context)
        assertFalse(ApiErrors.diagnostics(listOf(freeText), LoggingMode.DEVELOPMENT).single().containsKey("expression"))
    }

    @Test
    fun `free extension text and locations never become explanations`() {
        val hostile = "Bearer PRIVATE_TOKEN\n</script> /srv/private/server.key".repeat(10000)
        val diagnostic = Diagnostic(DiagnosticCodes.INVALID_TEMPLATE, hostile, location = hostile)
        val spoofed = Diagnostic(DiagnosticCodes.INVALID_TEMPLATE, RejectionReason.INVALID_TEMPLATE_SYNTAX_OR_UNSUPPORTED_OPERATION.message,
            location = "template:42")
        assertFalse(ApiErrors.diagnostics(listOf(spoofed), LoggingMode.DEVELOPMENT).single().containsKey("reason"))
        for (mode in LoggingMode.entries) {
            val events = mutableListOf<GenerationLogEvent>()
            val response = ApiErrors.diagnostics(List(1000) { diagnostic }, mode)
            assertEquals(64, response.size)
            assertTrue(response.all { it.keys == setOf("code", "severity", "message") })
            assertFalse(response.toString().contains("PRIVATE_TOKEN"))
            ApiErrors.logRejections(GenerationLogging(mode, "synthetic", events::add), List(1000) { diagnostic })
            assertEquals(32, events.size)
            assertTrue(events.all { it.requestId == "synthetic" && it.text.length < 4096 })
            assertFalse(events.joinToString { it.text }.contains("PRIVATE_TOKEN"))
        }
    }

    @Test
    fun `closed reasons retain safe coordinates but arbitrary paths and controls cannot escape`() {
        val reason = RejectionReason.INVALID_TEMPLATE_SYNTAX_OR_UNSUPPORTED_OPERATION
        val diagnostic = Diagnostic(DiagnosticCodes.INVALID_TEMPLATE, reason, location = "template:42", part = "word/document.xml")
        val development = ApiErrors.diagnostics(listOf(diagnostic), LoggingMode.DEVELOPMENT).single()
        assertEquals(reason.message, development["message"])
        assertEquals(reason.remediation, development["remediation"])
        assertEquals("template:42", development["location"])
        assertEquals("word/document.xml", development["part"])
        assertEquals(setOf("code", "severity", "message"), ApiErrors.diagnostics(listOf(diagnostic)).single().keys)
        for (hostile in listOf("/srv/PRIVATE_TOKEN.xml", "word/document.xml\nFORGED", "word/PRIVATE_TOKEN.xml", "xml:1:2\u202eSECRET", "template:" + "9".repeat(10000))) {
            val poisoned = Diagnostic(DiagnosticCodes.INVALID_TEMPLATE, reason, location = hostile, part = hostile)
            val response = ApiErrors.diagnostics(listOf(poisoned), LoggingMode.DEVELOPMENT).single()
            assertEquals("[other package part]", response["part"])
            assertFalse(response.containsKey("location"))
            assertFalse(response.toString().contains("PRIVATE_TOKEN"))
        }
    }

    @Test
    fun `every fixed reason is bounded plain application text`() {
        val mapper = ObjectMapper()
        val contract = OpenApiSchemaChecks(mapper.readTree(ApiDocumentation().jsonText))
        RejectionReason.entries.forEach { reason ->
            assertTrue(reason.name.length <= 128, reason.name)
            assertTrue(reason.message.length <= 256, reason.name)
            assertTrue(reason.remediation.length <= 256, reason.name)
            assertTrue((reason.message + reason.remediation).none { it.code < 32 || it in "<>\\" })
            for (mode in LoggingMode.entries) {
                val response = ApiErrors.diagnostics(listOf(Diagnostic(DiagnosticCodes.INVALID_TEMPLATE, reason,
                    location = "xml:99999999:99999999", part = "[Content_Types].xml")), mode).single()
                assertTrue(contract.matches("Diagnostic", mapper.valueToTree<JsonNode>(response)), reason.name)
            }
        }
    }
}

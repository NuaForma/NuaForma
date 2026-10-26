package dev.nuaforma.server

import com.fasterxml.jackson.databind.ObjectMapper
import dev.nuaforma.contracts.*
import dev.nuaforma.core.*
import dev.nuaforma.server.adapters.ktor.KtorServer
import dev.nuaforma.server.internal.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ConformancePolicyApiTest {
    @Test fun `real provider policy is identical through Java17 facet and REST without changing provider availability`() {
        val configType = Class.forName("dev.nuaforma.docx.pdf.NativePdfConfiguration")
        val type = Class.forName("dev.nuaforma.docx.pdf.DocxPdfProvider").asSubclass(DocumentProvider::class.java)
        for (enabled in listOf(true, false)) testApplication {
            val config = configType.getConstructor(List::class.java, Class.forName("dev.nuaforma.pdf.PdfConformanceValidator"), Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .newInstance(emptyList<Any>(), null, 1, enabled)
            val provider = type.getConstructor(configType).newInstance(config)
            val registry = ProviderRegistry.registered(listOf(ProviderRegistration(ProviderMetadata("pdf-policy", type.name, "0.1.0", ContractVersion.CURRENT)) { provider }))
            val expected = (provider as ConformancePolicyProvider).conformancePolicies().single()
            application { KtorServer(registry, ServerConfiguration()).installRoutes(this) }
            val response = client.get(ApiRoutes.CAPABILITIES)
            assertEquals(HttpStatusCode.OK, response.status)
            val mapper = ObjectMapper()
            val body = mapper.readTree(response.bodyAsText())
            val policy = body.path("providers").single().path("conformancePolicies").single()
            assertEquals(expected.outputFormat.id, policy.path("outputFormat").asText())
            assertEquals(expected.profiles, policy.path("profiles").map { it.asText() }.toSet())
            assertEquals(expected.validationEnabled, policy.path("validationEnabled").asBoolean())
            assertEquals(listOf("docx"), body.path("providers").single().path("available").path("writeFormats").map { it.asText() })
            assertTrue(OpenApiSchemaChecks(mapper.readTree(client.get(ApiRoutes.OPENAPI_JSON).bodyAsText())).matches("Capabilities", body))
            assertFalse(body.toString().contains("missing-policy-api"))
        }
    }

    @Test fun `absent invalid or failing extension metadata means unknown and does not leak provider details`() {
        val declared = ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.PDF), outputOptions = mapOf(DocumentFormat.PDF to
            listOf(OptionDefinition("profile", OptionType.STRING, "Profile", allowedValues = listOf("pdfa-3b")))))
        assertNull(ConformancePolicies.describe(mockk<DocumentProvider>(), declared, GenerationContext()))
        val provider = mockk<DocumentProvider>(moreInterfaces = arrayOf(ConformancePolicyProvider::class))
        val facet = provider as ConformancePolicyProvider
        val events = mutableListOf<GenerationLogEvent>()
        val context = GenerationContext(GenerationLogging(LoggingMode.PRODUCTION, null, events::add))
        for (policies in listOf(listOf(ConformancePolicy(DocumentFormat.DOCX, setOf("pdfa-3b"), false)),
            listOf(ConformancePolicy(DocumentFormat.PDF, setOf("private-profile"), false)),
            List(2) { ConformancePolicy(DocumentFormat.PDF, setOf("pdfa-3b"), false) })) {
            every { facet.conformancePolicies() } returns policies
            assertNull(ConformancePolicies.describe(provider, declared, context))
        }
        every { facet.conformancePolicies() } throws IllegalStateException("PRIVATE_TOKEN")
        assertNull(ConformancePolicies.describe(provider, declared, context))
        every { facet.conformancePolicies() } throws NoClassDefFoundError("PRIVATE_TOKEN")
        assertNull(ConformancePolicies.describe(provider, declared, context))
        every { facet.conformancePolicies() } throws java.util.ServiceConfigurationError("PRIVATE_TOKEN")
        assertNull(ConformancePolicies.describe(provider, declared, context))
        assertTrue(events.any { it.code == "CONFORMANCE_POLICY_UNAVAILABLE" })
        assertTrue(events.none { it.text.contains("PRIVATE_TOKEN") || it.text.contains("private-profile") })
        val checks = OpenApiSchemaChecks(ObjectMapper().readTree(ApiDocumentation().jsonText))
        val mapper = ObjectMapper()
        assertFalse(checks.matches("ConformancePolicy", mapper.readTree("""{"outputFormat":"pdf","profiles":[],"validationEnabled":false}""")))
        assertFalse(checks.matches("ConformancePolicy", mapper.readTree("""{"outputFormat":"pdf","profiles":["pdfa-3b","pdfa-3b"],"validationEnabled":false}""")))
    }
}

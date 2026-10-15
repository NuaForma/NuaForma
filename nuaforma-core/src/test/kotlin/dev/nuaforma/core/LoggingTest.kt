package dev.nuaforma.core

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class LoggingTest {
    private val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX))
    private open class Provider : DocumentProvider {
        override fun capabilities() = ProviderCapabilities(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX, DocumentFormat.PDF))
        override fun availability() = ProviderAvailability(setOf(DocumentFormat.DOCX), setOf(DocumentFormat.DOCX), listOf(Diagnostic(DiagnosticCodes.PROVIDER_UNAVAILABLE, "PRIVATE_PROVIDER_DETAIL")))
        override fun assess(requirements: GenerationRequirements) = CapabilityAssessment(AssessmentStatus.SUPPORTED)
        override fun generate(request: GenerationRequest, output: OutputStream, context: GenerationContext): GenerationResult = throw IllegalStateException("PRIVATE_DOCUMENT", IllegalArgumentException("password=PRIVATE"))
    }
    private fun registration(provider: DocumentProvider, required: ContractVersion = ContractVersion.CURRENT) =
        ProviderRegistration(ProviderMetadata("synthetic", provider.javaClass.name, "0.1.0", required)) { provider }

    private class ContextualProvider(private val cancelled: AtomicBoolean, private val seen: MutableList<String?>) : Provider(), ProviderReadiness {
        override fun availability(context: GenerationContext): ProviderAvailability {
            seen += context.logging.requestId
            context.checkActive()
            return super.availability()
        }
        override fun assess(requirements: GenerationRequirements, context: GenerationContext): CapabilityAssessment {
            seen += context.logging.requestId
            cancelled.set(true)
            context.checkActive()
            return super.assess(requirements)
        }
    }
    private class UnavailableProvider : Provider() {
        override fun availability() = ProviderAvailability(emptySet(), emptySet(), listOf(Diagnostic(DiagnosticCodes.PROVIDER_UNAVAILABLE, "PRIVATE_UNAVAILABLE")))
    }
    private class FailingProvider : Provider() { override fun capabilities(): ProviderCapabilities = throw IllegalStateException("PRIVATE_INITIALIZATION") }

    @Test
    fun `fully unavailable and initialization failure retain safe provider identity`() {
        val events = mutableListOf<GenerationLogEvent>()
        val logging = GenerationLogging(sink = events::add)
        ProviderRegistry.registered(listOf(registration(UnavailableProvider())), logging)
        assertTrue(events.any { it.code == "PROVIDER_READINESS" && it.text.contains("state=\"unavailable\"") && it.text.contains("synthetic") })
        ProviderRegistry.registered(listOf(registration(FailingProvider())), logging)
        assertTrue(events.any { it.code == "PROVIDER_INITIALIZATION_FAILED" && it.text.contains("synthetic") && it.text.contains("IllegalStateException") })
        assertFalse(events.any { it.text.contains("PRIVATE") })
    }

    @Test
    fun `startup and partial readiness have safe identity capabilities reasons and bounded repeats`() {
        val events = mutableListOf<GenerationLogEvent>()
        val logging = GenerationLogging(LoggingMode.PRODUCTION, null, events::add)
        val registry = ProviderRegistry.registered(listOf(registration(Provider())), logging)
        assertTrue(events.any { it.code == "PROVIDER_READINESS" && it.text.contains("partial") && it.text.contains("synthetic") && it.text.contains("pdf") })
        assertTrue(events.any { it.text.contains("Check the configured renderer") })
        assertFalse(events.any { it.text.contains("PRIVATE") || it.requestId != null })
        val request = GenerationContext(GenerationLogging(LoggingMode.PRODUCTION, "request", events::add))
        repeat(100) { registry.select(requirements, "synthetic", request) }
        assertEquals(2, events.count { it.code == "PROVIDER_READINESS" })
        assertEquals("request", events.last().requestId)
        assertThrows(GenerationException::class.java) { registry.generate(GenerationRequest(byteArrayOf().inputStream(), requirements), OutputStream.nullOutputStream(), request, "synthetic") }
        assertTrue(events.any { it.code == "PROVIDER_GENERATION_FAILED" && it.text.contains("IllegalStateException") && it.text.contains("synthetic") })
        assertFalse(events.any { it.text.contains("PRIVATE") })
    }

    @Test
    fun `incompatible absent and failing providers are explained without instantiating incompatible code`() {
        val events = mutableListOf<GenerationLogEvent>()
        val logging = GenerationLogging(sink = events::add)
        val initialized = AtomicBoolean()
        val metadata = ProviderMetadata("future", Provider::class.java.name, "1.0.0", ContractVersion(99, 0))
        val registry = ProviderRegistry.registered(listOf(ProviderRegistration(metadata) { initialized.set(true); Provider() }), logging)
        assertFalse(initialized.get()); assertTrue(registry.providers.isEmpty())
        assertTrue(events.any { it.code == "NO_PROVIDERS" })
        assertTrue(events.any { it.text.contains("INCOMPATIBLE_CONTRACT") && it.text.contains("future") })
    }

    @Test
    fun `optional contextual readiness receives same identity and cancellation is not swallowed`() {
        val cancelled = AtomicBoolean()
        val seen = mutableListOf<String?>()
        val provider = ContextualProvider(cancelled, seen)
        val registry = ProviderRegistry.registered(listOf(registration(provider)), GenerationLogging())
        val context = GenerationContext(GenerationLimits(), CancellationToken { cancelled.get() }, null, java.time.Clock.systemUTC(), GenerationLogging(requestId = "contextual"))
        val failure = assertThrows(GenerationException::class.java) { registry.select(requirements, null, context) }
        assertEquals(DiagnosticCodes.CANCELLED, failure.diagnostics.single().code)
        assertEquals(listOf(null, "contextual", "contextual"), seen)
    }
}

package dev.nuaforma.core;

import dev.nuaforma.contracts.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class JavaCoreApiTest {
    @Test
    void javaCanRegisterSelectAndGenerateThroughPublicApi() throws Exception {
        var metadata = new ProviderMetadata("java-fixture", Fixture.class.getName(), "1.0.0", ContractVersion.CURRENT);
        var registry = ProviderRegistry.registered(List.of(new ProviderRegistration(metadata, Fixture::new)));
        var requirements = new GenerationRequirements(new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.DOCX));
        assertEquals("java-fixture", registry.select(requirements).getMetadata().getId());
        var output = new ByteArrayOutputStream();
        var result = registry.generate(new GenerationRequest(new ByteArrayInputStream(new byte[0]), requirements), output, new GenerationContext());
        assertEquals("fixture", output.toString(StandardCharsets.UTF_8));
        assertEquals(DocumentFormat.DOCX, result.getOutput().getFormat());
        assertThrows(GenerationException.class, () -> registry.select(requirements, "missing"));
    }

    @Test
    void mismatchedFactoryIdentityAndExpandedAvailabilityAreRejected() {
        var wrongClass = new ProviderMetadata("mismatch", "example.Other", "1.0.0", ContractVersion.CURRENT);
        assertTrue(ProviderRegistry.registered(List.of(new ProviderRegistration(wrongClass, Fixture::new))).getProviders().isEmpty());
        var badMetadata = new ProviderMetadata("expanded", ExpandedAvailability.class.getName(), "1.0.0", ContractVersion.CURRENT);
        var result = ProviderRegistry.registered(List.of(new ProviderRegistration(badMetadata, ExpandedAvailability::new)));
        assertTrue(result.getProviders().isEmpty());
        assertEquals("INVALID_PROVIDER", result.getDiagnostics().get(0).getCode().getId());
    }

    @Test
    void lateAvailabilityAndAssessmentErrorsReturnSanitizedDiagnostics() throws Exception {
        var fixture = new Fixture();
        var metadata = new ProviderMetadata("changing", Fixture.class.getName(), "1.0.0", ContractVersion.CURRENT);
        var registry = ProviderRegistry.registered(List.of(new ProviderRegistration(metadata, () -> fixture)));
        var requirements = new GenerationRequirements(new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.DOCX));
        fixture.fault = true;
        var error = assertThrows(GenerationException.class, () -> registry.select(requirements));
        assertTrue(error.getDiagnostics().stream().anyMatch(d -> d.getCode().getId().equals("INVALID_PROVIDER")));
        assertTrue(error.getDiagnostics().stream().noneMatch(d -> d.getMessage().contains("SECRET")));
    }

    @Test
    void unexpectedGenerationFailuresAreSanitizedAndIoFailuresRemainIo() throws Exception {
        var fixture = new Fixture();
        var metadata = new ProviderMetadata("faulting", Fixture.class.getName(), "1.0.0", ContractVersion.CURRENT);
        var registry = ProviderRegistry.registered(List.of(new ProviderRegistration(metadata, () -> fixture)));
        var requirements = new GenerationRequirements(new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.DOCX));
        var request = new GenerationRequest(new ByteArrayInputStream(new byte[0]), requirements);
        fixture.generationFault = true;
        var error = assertThrows(GenerationException.class, () -> registry.generate(request, new ByteArrayOutputStream(), new GenerationContext()));
        assertEquals("INVALID_PROVIDER", error.getDiagnostics().get(0).getCode().getId());
        assertFalse(error.getDiagnostics().get(0).getMessage().contains("SECRET"));
        fixture.generationFault = false;
        var failingOutput = new OutputStream() { public void write(int value) throws IOException { throw new IOException("Synthetic I/O failure"); } };
        assertThrows(IOException.class, () -> registry.generate(request, failingOutput, new GenerationContext()));
    }

    // Synthetic fixture for orchestration, not a DOCX renderer.
    static class Fixture implements DocumentProvider {
        boolean fault;
        boolean generationFault;
        public ProviderCapabilities capabilities() {
            return new ProviderCapabilities(Set.of(DocumentFormat.DOCX), Set.of(DocumentFormat.DOCX));
        }
        public ProviderAvailability availability() {
            return new ProviderAvailability(Set.of(DocumentFormat.DOCX), Set.of(DocumentFormat.DOCX));
        }
        public CapabilityAssessment assess(GenerationRequirements requirements) {
            if (fault) throw new IllegalStateException("SECRET");
            return new CapabilityAssessment(AssessmentStatus.SUPPORTED);
        }
        public GenerationResult generate(GenerationRequest request, OutputStream output, GenerationContext context) throws IOException {
            if (generationFault) throw new IllegalStateException("SECRET");
            output.write("fixture".getBytes(StandardCharsets.UTF_8));
            return new GenerationResult(request.getRequirements().getOutput());
        }
    }
    static class ExpandedAvailability extends Fixture {
        @Override public ProviderAvailability availability() {
            return new ProviderAvailability(Set.of(DocumentFormat.DOCX), Set.of(DocumentFormat.PDF));
        }
    }
}

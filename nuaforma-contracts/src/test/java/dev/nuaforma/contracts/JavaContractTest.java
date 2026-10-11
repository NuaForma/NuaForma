package dev.nuaforma.contracts;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JavaContractTest {
    @Test
    void javaCanDescribeNestedRequestsAndProviderOptions() {
        var output = new FormatRequest(DocumentFormat.PDF, Map.of("version", "1.7", "embedFonts", true));
        var options = new GenerationOptions(new TextWatermark("CONCEPT"));
        var requirements = new GenerationRequirements(new FormatRequest(DocumentFormat.DOCX), output, options);
        var data = new HashMap<String, Object>();
        data.put("customer", Map.of("name", "Example", "customField", List.of(1, 2)));
        data.put("explicitNull", null);
        var request = new GenerationRequest(new ByteArrayInputStream(new byte[0]), requirements, data);
        data.clear();

        assertTrue(request.getData().containsKey("explicitNull"));
        assertEquals(List.of(1, 2), ((Map<?, ?>) request.getData().get("customer")).get("customField"));
        assertEquals("1.7", request.getRequirements().getOutput().getOptions().get("version"));
        assertEquals("CONCEPT", options.getWatermark().getText());
        var definition = new OptionDefinition("version", OptionType.STRING, "PDF version", false, "1.7", List.of("1.7", "2.0"));
        assertFalse(definition.getRequired());
        assertEquals("1.7", definition.getDefaultValue());
        assertTrue(new ContractVersion(1, 9).accepts(new ContractVersion(1, 2)));
    }

    @Test
    void javaProviderLeavesCallerStreamsOpenAndClosesOpenedResources() throws Exception {
        var input = new TrackingInput("synthetic".getBytes(StandardCharsets.UTF_8));
        var output = new TrackingOutput();
        var openedResource = new TrackingInput(new byte[] {1});
        ResourceSource source = () -> openedResource;
        var request = new GenerationRequest(
            input, docxRequirements(), Map.of("reference", "NF-001"), Map.of("fixture-resource", source)
        );
        var result = new FixtureProvider().generate(request, output, new GenerationContext());

        assertEquals("NF-001", output.toString(StandardCharsets.UTF_8));
        assertEquals(DocumentFormat.DOCX, result.getOutput().getFormat());
        assertTrue(result.getWarnings().isEmpty());
        assertFalse(input.closed);
        assertFalse(output.closed);
        assertTrue(openedResource.closed);
    }

    @Test
    void failuresAndCancellationDoNotCloseCallerStreamsOrPretendToRollback() throws Exception {
        var input = new TrackingInput(new byte[] {1});
        var output = new TrackingOutput();
        var request = new GenerationRequest(input, docxRequirements());
        var faulting = new FixtureProvider() {
            @Override
            public GenerationResult generate(GenerationRequest value, OutputStream destination, GenerationContext context)
                    throws GenerationException, IOException {
                destination.write(1);
                throw new GenerationException(List.of(new Diagnostic(DiagnosticCodes.INVALID_TEMPLATE, "Synthetic failure")));
            }
        };
        var failure = assertThrows(GenerationException.class, () -> faulting.generate(request, output, new GenerationContext()));
        assertEquals(DiagnosticCodes.INVALID_TEMPLATE, failure.getDiagnostics().get(0).getCode());
        assertEquals(1, output.size());
        assertFalse(input.closed);
        assertFalse(output.closed);

        var cancelledOutput = new TrackingOutput();
        var cancelled = new GenerationContext(new GenerationLimits(), () -> true);
        assertThrows(GenerationException.class, () -> new FixtureProvider().generate(request, cancelledOutput, cancelled));
        assertEquals(0, cancelledOutput.size());
        assertFalse(input.closed);
        assertFalse(cancelledOutput.closed);
    }

    @Test
    void descriptorReadingDoesNotInitializeProviderClasses() throws Exception {
        var className = JavaContractTest.class.getName() + "$InitializingProvider";
        var descriptor = "provider.id=fixture\nprovider.class=" + className +
            "\nprovider.version=0.1.0-SNAPSHOT\ncontract.major=1\ncontract.minor=2\n";
        assertEquals(0, InitializationCounter.count);
        var metadata = ProviderMetadata.read(new ByteArrayInputStream(descriptor.getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(className, metadata.getImplementationClass());
        assertFalse(new ContractVersion(1, 1).accepts(metadata.getRequiredContract()));
        assertEquals(0, InitializationCounter.count);

        Class.forName(className);
        assertEquals(1, InitializationCounter.count);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void invalidJavaCollectionsAreRejected() {
        Map badKeys = Map.of(1, "not a string key");
        assertThrows(IllegalArgumentException.class, () -> new FormatRequest(DocumentFormat.PDF, badKeys));
        var nullFormats = new java.util.HashSet<DocumentFormat>();
        nullFormats.add(null);
        assertThrows(IllegalArgumentException.class, () -> new ProviderCapabilities(nullFormats, Set.of(DocumentFormat.DOCX)));
        var nullResources = new HashMap<String, ResourceSource>();
        nullResources.put("font", null);
        assertThrows(IllegalArgumentException.class, () -> new GenerationRequest(
            new ByteArrayInputStream(new byte[0]), docxRequirements(), Map.of(), nullResources
        ));
        Map nullResourceKey = new HashMap();
        nullResourceKey.put(null, (ResourceSource) () -> new ByteArrayInputStream(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new GenerationRequest(
            new ByteArrayInputStream(new byte[0]), docxRequirements(), Map.of(), nullResourceKey
        ));
    }

    private static GenerationRequirements docxRequirements() {
        return new GenerationRequirements(new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.DOCX));
    }

    private static class TrackingInput extends ByteArrayInputStream {
        boolean closed;
        TrackingInput(byte[] data) { super(data); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    private static class TrackingOutput extends ByteArrayOutputStream {
        boolean closed;
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    private static class InitializationCounter { static int count; }

    public static class InitializingProvider extends FixtureProvider {
        static { InitializationCounter.count++; }
    }

    /** Synthetic I/O fixture; not an implementation of real DOCX processing. */
    public static class FixtureProvider implements DocumentProvider {
        @Override public ProviderCapabilities capabilities() {
            return new ProviderCapabilities(Set.of(DocumentFormat.DOCX), Set.of(DocumentFormat.DOCX));
        }
        @Override public ProviderAvailability availability() {
            return new ProviderAvailability(Set.of(DocumentFormat.DOCX), Set.of(DocumentFormat.DOCX));
        }
        @Override public CapabilityAssessment assess(GenerationRequirements request) {
            if (!capabilities().supports(request.getInput().getFormat(), request.getOutput().getFormat())) {
                return new CapabilityAssessment(AssessmentStatus.UNSUPPORTED, List.of(
                    new Diagnostic(DiagnosticCodes.UNSUPPORTED_OUTPUT, "Synthetic format rejection")
                ));
            }
            if (!request.getInput().getOptions().isEmpty() || !request.getOutput().getOptions().isEmpty() ||
                    request.getOptions().getWatermark() != null || !request.getOptions().getExtensions().isEmpty()) {
                return new CapabilityAssessment(AssessmentStatus.UNSUPPORTED, List.of(
                    new Diagnostic(DiagnosticCodes.UNSUPPORTED_OPTION, "Synthetic option rejection")
                ));
            }
            return new CapabilityAssessment(AssessmentStatus.SUPPORTED);
        }
        @Override public GenerationResult generate(GenerationRequest request, OutputStream output, GenerationContext context)
                throws GenerationException, IOException {
            context.checkActive();
            var assessment = assess(request.getRequirements());
            if (assessment.getStatus() != AssessmentStatus.SUPPORTED) {
                throw new GenerationException(assessment.getDiagnostics());
            }
            for (var source : request.getResources().values()) {
                try (var resource = source.openStream()) { resource.read(); }
            }
            var reference = request.getData().getOrDefault("reference", "fixture");
            output.write(reference.toString().getBytes(StandardCharsets.UTF_8));
            return new GenerationResult(request.getRequirements().getOutput());
        }
    }
}

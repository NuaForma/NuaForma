package independent;

import dev.nuaforma.contracts.*;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Compiled and packaged independently during tests; synthetic bytes, never a real renderer.
public class PROVIDER_CLASS implements DocumentProvider {
    static { System.setProperty("nuaforma.fixture.initialized.PROVIDER_CLASS", "yes"); }

    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(Set.of(DocumentFormat.DOCX), Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), Map.of(), Map.of(
            DocumentFormat.PDF, List.of(
                new OptionDefinition("version", OptionType.STRING, "Synthetic version", false, "1.7", List.of("1.7", "2.0")),
                new OptionDefinition("profile", OptionType.STRING, "Synthetic profile", false, "standard", List.of("standard", "archive"))
            )
        ));
    }
    public ProviderAvailability availability() {
        if ("false".equals(System.getProperty("nuaforma.fixture.pdf.PROVIDER_CLASS"))) {
            return new ProviderAvailability(Set.of(DocumentFormat.DOCX), Set.of(DocumentFormat.DOCX), List.of(
                new Diagnostic(DiagnosticCodes.PROVIDER_UNAVAILABLE, "Synthetic converter unavailable")
            ));
        }
        return new ProviderAvailability(Set.of(DocumentFormat.DOCX), Set.of(DocumentFormat.DOCX, DocumentFormat.PDF));
    }
    public CapabilityAssessment assess(GenerationRequirements request) {
        if (!request.getInput().getOptions().isEmpty() || request.getOptions().getWatermark() != null ||
                !request.getOptions().getExtensions().isEmpty()) return rejected();
        var options = request.getOutput().getOptions();
        if (request.getOutput().getFormat().equals(DocumentFormat.DOCX)) {
            return options.isEmpty() ? new CapabilityAssessment(AssessmentStatus.SUPPORTED) : rejected();
        }
        if (!Set.of("version", "profile").containsAll(options.keySet())) return rejected();
        var version = options.getOrDefault("version", "1.7");
        var profile = options.getOrDefault("profile", "standard");
        if (!(version instanceof String) || !(profile instanceof String)) return rejected();
        if (!Set.of("1.7", "2.0").contains(version) || !Set.of("standard", "archive").contains(profile) ||
                (profile.equals("archive") && !version.equals("1.7"))) return rejected();
        return new CapabilityAssessment(AssessmentStatus.SUPPORTED);
    }
    private CapabilityAssessment rejected() {
        return new CapabilityAssessment(AssessmentStatus.UNSUPPORTED, List.of(
            new Diagnostic(DiagnosticCodes.UNSUPPORTED_OPTION, "Synthetic option rejection")
        ));
    }
    public GenerationResult generate(GenerationRequest request, OutputStream output, GenerationContext context)
            throws GenerationException, IOException {
        context.checkActive();
        if (!availability().supports(request.getRequirements().getInput().getFormat(), request.getRequirements().getOutput().getFormat())) {
            throw new GenerationException(List.of(new Diagnostic(DiagnosticCodes.PROVIDER_UNAVAILABLE, "Synthetic availability changed")));
        }
        var assessment = assess(request.getRequirements());
        if (assessment.getStatus() != AssessmentStatus.SUPPORTED) throw new GenerationException(assessment.getDiagnostics());
        output.write(request.getData().getOrDefault("reference", "fixture").toString().getBytes(StandardCharsets.UTF_8));
        return new GenerationResult(request.getRequirements().getOutput());
    }
}

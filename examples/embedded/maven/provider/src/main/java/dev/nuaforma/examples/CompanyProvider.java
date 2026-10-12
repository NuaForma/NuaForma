package dev.nuaforma.examples;

import dev.nuaforma.contracts.*;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** A deliberately small company-owned format, implemented against contracts only. */
public final class CompanyProvider implements DocumentProvider {
    public static final DocumentFormat FORMAT = new DocumentFormat("company-note");
    public ProviderCapabilities capabilities() { return new ProviderCapabilities(Set.of(FORMAT), Set.of(FORMAT)); }
    public ProviderAvailability availability() { return new ProviderAvailability(Set.of(FORMAT), Set.of(FORMAT)); }
    public CapabilityAssessment assess(GenerationRequirements requirements) {
        boolean supported = capabilities().supports(requirements.getInput().getFormat(), requirements.getOutput().getFormat())
            && requirements.getInput().getOptions().isEmpty() && requirements.getOutput().getOptions().isEmpty()
            && requirements.getOptions().getWatermark() == null && requirements.getOptions().getExtensions().isEmpty();
        return supported ? new CapabilityAssessment(AssessmentStatus.SUPPORTED) : new CapabilityAssessment(
            AssessmentStatus.UNSUPPORTED, List.of(new Diagnostic(DiagnosticCodes.UNSUPPORTED_OPTION, "Company note accepts only its plain format pair")));
    }
    public GenerationResult generate(GenerationRequest request, OutputStream output, GenerationContext context) throws GenerationException, IOException {
        context.checkActive();
        if (assess(request.getRequirements()).getStatus() != AssessmentStatus.SUPPORTED) {
            throw new GenerationException(assess(request.getRequirements()).getDiagnostics());
        }
        if (!request.getResources().isEmpty()) fail(DiagnosticCodes.UNSUPPORTED_OPTION, "Company note accepts no resources");
        int limit = (int) Math.min(context.getLimits().getMaxTemplateBytes(), 65536L);
        byte[] source = request.getTemplate().readNBytes(limit + 1);
        if (source.length > limit) fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "Company note template exceeds its byte limit");
        Object name = request.getData().get("name");
        if (!(name instanceof String)) fail(DiagnosticCodes.INVALID_REQUEST, "Company note requires a string name");
        if (((String) name).length() > 65536) fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "Company note name exceeds its size limit");
        String template = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source)).toString();
        if (!template.equals("Company: {name}")) fail(DiagnosticCodes.INVALID_TEMPLATE, "Unsupported company note template");
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(template.replace("{name}", (String) name)));
        byte[] result = new byte[encoded.remaining()]; encoded.get(result);
        if (result.length > Math.min(context.getLimits().getMaxOutputBytes(), context.getLimits().getMaxExpandedBytes())) {
            fail(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, "Company note output exceeds its byte limit");
        }
        context.checkActive(); output.write(result); context.checkActive();
        return new GenerationResult(request.getRequirements().getOutput());
    }
    private static void fail(DiagnosticCode code, String message) throws GenerationException {
        throw new GenerationException(List.of(new Diagnostic(code, message)));
    }
}

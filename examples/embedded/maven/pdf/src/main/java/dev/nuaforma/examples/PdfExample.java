package dev.nuaforma.examples;

import dev.nuaforma.contracts.*;
import dev.nuaforma.core.*;
import dev.nuaforma.docx.pdf.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class PdfExample {
    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args.length != 2) throw new IllegalArgumentException("Supply source DOCX and output PDF with trusted validator/font JVM settings, or no arguments to test unavailable conversion");
        ProviderMetadata metadata;
        try (var descriptor = DocxPdfProvider.class.getResourceAsStream("/" + ProviderMetadata.resourcePath(DocxPdfProvider.class.getName()))) {
            metadata = ProviderMetadata.read(descriptor);
        }
        var registry = ProviderRegistry.registered(List.of(new ProviderRegistration(metadata,
            DocxPdfProvider::new)));
        var requirements = new GenerationRequirements(new FormatRequest(DocumentFormat.DOCX),
            new FormatRequest(DocumentFormat.PDF, Map.of("profile", "pdfa-3b")));
        if (args.length == 0) {
            if (registry.getProviders().get(0).getProvider().assess(requirements).getStatus() != AssessmentStatus.UNAVAILABLE) {
                throw new IllegalStateException("Missing validator runtime must leave PDF unavailable");
            }
            registry.select(new GenerationRequirements(new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.DOCX)));
            System.out.println("Independent PDF example compiled; missing-runtime rejection and DOCX availability passed (no PDF rendered)");
            return;
        }
        try (var template = Files.newInputStream(Path.of(args[0])); var output = new ByteArrayOutputStream()) {
            var result = registry.generate(new GenerationRequest(template, requirements,
                Map.of("customer", Map.of("name", " Ada "))), output, new GenerationContext());
            if (!result.getOutput().getFormat().equals(DocumentFormat.PDF)
                || !result.getOutput().getOptions().get("profile").equals("pdfa-3b")) throw new IllegalStateException("Wrong achieved PDF profile");
            Files.write(Path.of(args[1]), output.toByteArray());
        }
        System.out.println("Independent Maven PDF/A-3b example passed with the configured native engine and independent validator");
    }
}

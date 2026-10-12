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
        if (args.length != 0 && args.length != 2) throw new IllegalArgumentException("Supply isolated launcher and output PDF, or no arguments to test unavailable conversion");
        Path launcher = args.length == 2 ? Path.of(args[0]) : Path.of("build/missing-isolated-launcher").toAbsolutePath();
        ProviderMetadata metadata;
        try (var descriptor = DocxPdfProvider.class.getResourceAsStream("/" + ProviderMetadata.resourcePath(DocxPdfProvider.class.getName()))) {
            metadata = ProviderMetadata.read(descriptor);
        }
        var registry = ProviderRegistry.registered(List.of(new ProviderRegistration(metadata,
            () -> new DocxPdfProvider(new LibreOfficeConfiguration(launcher)))));
        var requirements = new GenerationRequirements(new FormatRequest(DocumentFormat.DOCX),
            new FormatRequest(DocumentFormat.PDF, Map.of("profile", "pdfa-3b")));
        if (args.length == 0) {
            if (registry.getProviders().get(0).getProvider().assess(requirements).getStatus() != AssessmentStatus.UNAVAILABLE) {
                throw new IllegalStateException("Missing isolated runtime must leave PDF unavailable");
            }
            registry.select(new GenerationRequirements(new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.DOCX)));
            System.out.println("Independent PDF example compiled; missing-runtime rejection and DOCX availability passed (no PDF rendered)");
            return;
        }
        try (var template = new ByteArrayInputStream(ExampleTemplate.create()); var output = new ByteArrayOutputStream()) {
            var result = registry.generate(new GenerationRequest(template, requirements,
                Map.of("customer", Map.of("name", " Ada "))), output, new GenerationContext());
            if (!result.getOutput().getFormat().equals(DocumentFormat.PDF)
                || !result.getOutput().getOptions().get("profile").equals("pdfa-3b")) throw new IllegalStateException("Wrong achieved PDF profile");
            Files.write(Path.of(args[1]), output.toByteArray());
        }
        System.out.println("Independent Maven PDF/A-3b example passed with the configured isolated runtime");
    }
}

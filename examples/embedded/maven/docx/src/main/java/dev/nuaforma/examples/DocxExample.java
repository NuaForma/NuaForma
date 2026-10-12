package dev.nuaforma.examples;

import dev.nuaforma.contracts.*;
import dev.nuaforma.core.ProviderRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipInputStream;

public final class DocxExample {
    public static void main(String[] args) throws Exception {
        var registry = ProviderRegistry.discover();
        if (!registry.getDiagnostics().isEmpty()) throw new IllegalStateException("Unexpected discovery diagnostics");
        var requirements = new GenerationRequirements(new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.DOCX));
        try (var template = new ByteArrayInputStream(ExampleTemplate.create()); var output = new ByteArrayOutputStream()) {
            var result = registry.generate(new GenerationRequest(template, requirements,
                Map.of("customer", Map.of("name", " Ada "))), output, new GenerationContext(), "nuaforma-docx");
            if (!result.getOutput().getFormat().equals(DocumentFormat.DOCX)) throw new IllegalStateException("Wrong format");
            boolean found = false;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    if (entry.getName().equals("word/document.xml")) {
                        String document = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                        found = document.contains("Ada") && document.contains("<w:b") && !document.contains("{trim");
                    }
                }
            }
            if (!found) throw new IllegalStateException("Expected formatted content is missing");
            if (args.length == 1) Files.write(Path.of(args[0]), output.toByteArray());
        }
        System.out.println("Independent Maven DOCX example passed");
    }
}

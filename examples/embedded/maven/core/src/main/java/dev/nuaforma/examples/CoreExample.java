package dev.nuaforma.examples;

import dev.nuaforma.contracts.*;
import dev.nuaforma.core.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class CoreExample {
    public static void main(String[] args) throws Exception {
        var discovered = ProviderRegistry.discover();
        if (!discovered.getDiagnostics().isEmpty()) throw new IllegalStateException("Unexpected discovery diagnostics");
        var registered = ProviderRegistry.registered(List.of(new ProviderRegistration(new ProviderMetadata(
            "example-company", CompanyProvider.class.getName(), "0.1.0", ContractVersion.CURRENT), CompanyProvider::new)));
        for (var registry : List.of(discovered, registered)) {
            var requirements = new GenerationRequirements(new FormatRequest(CompanyProvider.FORMAT), new FormatRequest(CompanyProvider.FORMAT));
            try (var template = new java.io.ByteArrayInputStream("Company: {name}".getBytes(StandardCharsets.UTF_8));
                 var output = new ByteArrayOutputStream()) {
                registry.generate(new GenerationRequest(template, requirements, Map.of("name", "Ada")), output, new GenerationContext());
                if (!output.toString(StandardCharsets.UTF_8).equals("Company: Ada")) throw new IllegalStateException("Wrong company output");
            }
        }
        System.out.println("Independent core/provider examples passed");
    }
}

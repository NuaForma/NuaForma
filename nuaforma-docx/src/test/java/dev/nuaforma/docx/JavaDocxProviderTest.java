package dev.nuaforma.docx;

import dev.nuaforma.contracts.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaDocxProviderTest {
    @Test
    void providerIsUsableThroughJavaContracts() {
        DocumentProvider provider = new DocxProvider();
        GenerationRequirements requirements = new GenerationRequirements(
            new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.DOCX));
        assertEquals(AssessmentStatus.SUPPORTED, provider.assess(requirements).getStatus());
        assertTrue(provider.availability().supports(DocumentFormat.DOCX, DocumentFormat.DOCX));
    }
}

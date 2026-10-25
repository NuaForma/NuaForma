package dev.nuaforma.docx.pdf;

import dev.nuaforma.contracts.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class NativePdfJavaApiTest {
    @Test void registeredProviderRequiresOnlyNativeConfiguration() {
        DocumentProvider provider = new DocxPdfProvider(new NativePdfConfiguration(List.of()));
        assertEquals(Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), provider.capabilities().getWriteFormats());
        assertEquals(Set.of(DocumentFormat.DOCX), provider.availability().getWriteFormats());
        assertEquals(AssessmentStatus.UNAVAILABLE, provider.assess(new GenerationRequirements(
            new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.PDF))).getStatus());
    }
}

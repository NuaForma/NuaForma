package dev.nuaforma.docx.pdf;

import dev.nuaforma.contracts.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PdfJavaApiTest {
    @Test
    void javaConsumersCanConfigureAndAssessWithoutRendererTypes() {
        DocumentProvider provider = new DocxPdfProvider(new LibreOfficeConfiguration(Path.of("/nonexistent/nf-renderer"), 2));
        assertEquals(Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), provider.capabilities().getWriteFormats());
        assertEquals(Set.of(DocumentFormat.DOCX), provider.availability().getWriteFormats());
        GenerationRequirements request = new GenerationRequirements(new FormatRequest(DocumentFormat.DOCX),
            new FormatRequest(DocumentFormat.PDF, Map.of("version", "1.4")));
        assertEquals(AssessmentStatus.UNSUPPORTED, provider.assess(request).getStatus());
    }
}

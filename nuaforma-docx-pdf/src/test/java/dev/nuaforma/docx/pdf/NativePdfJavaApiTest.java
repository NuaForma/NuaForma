package dev.nuaforma.docx.pdf;

import dev.nuaforma.contracts.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class NativePdfJavaApiTest {
    @Test void sourceDiagnosticsAreAvailableToJavaWithoutChangingExistingConstructors() {
        var source = new DocumentSourceContext("Unsupported paragraph border", "/w:p[1]/w:pPr[1]/w:pBdr[1]", "w:pBdr", "Nearby text");
        var original = new Diagnostic(DiagnosticCodes.UNSUPPORTED_INPUT, RejectionReason.NATIVE_DOCX_PROFILE_UNSUPPORTED);
        var enriched = original.withSourceContext(source).withDocumentLocation("paragraph:1");
        assertNull(original.getSourceContext());
        assertSame(source, enriched.getSourceContext());
        assertEquals("Nearby text", enriched.getSourceContext().getExcerpt());
        assertInstanceOf(DocumentDiagnosticProvider.class, new DocxPdfProvider(new NativePdfConfiguration(List.of())));
    }

    @Test void registeredProviderRequiresOnlyNativeConfiguration() {
        DocumentProvider provider = new DocxPdfProvider(new NativePdfConfiguration(List.of()));
        assertEquals(Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), provider.capabilities().getWriteFormats());
        assertEquals(Set.of(DocumentFormat.DOCX), provider.availability().getWriteFormats());
        assertEquals(AssessmentStatus.UNAVAILABLE, provider.assess(new GenerationRequirements(
            new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.PDF))).getStatus());
    }
}

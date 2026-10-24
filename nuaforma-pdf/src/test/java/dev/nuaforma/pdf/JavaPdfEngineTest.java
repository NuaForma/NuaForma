package dev.nuaforma.pdf;

import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPdfEngineTest {
    @Test
    void javaCallerCanRenderTypedTabs() throws IOException {
        var style = new PdfTextStyle("regular", 10);
        var tabs = new PdfTabSettings(List.of(new PdfTabStop(180, PdfTabAlignment.RIGHT, PdfTabLeader.DOT)));
        var format = new PdfParagraphFormat(PdfAlignment.LEFT, 0, 0, 0, 14f, false, false, false, false, false, null, tabs);
        var paragraph = new PdfParagraph(List.of(new PdfTextRun("Java", style), new PdfTab(style), new PdfTextRun("Value", style)), 0, 0, format);
        byte[] font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        var document = new PdfDocument(new PdfPage(300, 200, new PdfMargins(20, 20, 20, 20)), List.of(paragraph));
        try (var pdf = Loader.loadPDF(new PdfEngine().render(document, List.of(new PdfFont("regular", font))))) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("Value"));
        }
    }

    @Test
    void javaCallerCanResolveAndRenderSemanticNumbering() throws IOException {
        var definition = new PdfListDefinition("java", List.of(new PdfListLevel(PdfNumberFormat.DECIMAL, "%1.")));
        var value = PdfNumbering.resolve(List.of(definition), List.of(new PdfListStep("java", 0))).get(0);
        var style = new PdfTextStyle("regular", 10);
        var label = new PdfListLabel(value, style);
        var paragraph = new PdfParagraph(List.of(new PdfTextRun("Numbered Java item", style)), 0, 0,
            new PdfParagraphFormat(PdfAlignment.LEFT, 36, 0, -18, 14f), label);
        byte[] font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        var document = new PdfDocument(new PdfPage(300, 200, new PdfMargins(20, 20, 20, 20)), List.of(paragraph));
        try (var pdf = Loader.loadPDF(new PdfEngine().render(document, List.of(new PdfFont("regular", font))))) {
            var text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("1.") && text.contains("Numbered Java item"));
        }
    }

    @Test
    void javaCallerCanCreateAPositionedTextFrame() throws IOException {
        byte[] font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        var paragraph = new PdfParagraph(List.of(new PdfTextRun("Positioned Java frame", new PdfTextStyle("regular", 10))),
            0, 0, new PdfParagraphFormat(PdfAlignment.LEFT, 0, 0, 0, 16f));
        var box = new PdfTextBox(30, 40, 200, 60, List.of(paragraph), PdfBoxAnchor.PAGE);
        var document = new PdfDocument(new PdfPage(300, 200, new PdfMargins(20, 20, 20, 20)), List.of(box));
        try (var pdf = Loader.loadPDF(new PdfEngine().render(document, List.of(new PdfFont("regular", font))))) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("Positioned Java frame"));
        }
    }

    @Test
    void java17CallerRendersWithoutKotlinOrVendorTypesInTheFacade() throws IOException {
        byte[] font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        var document = new PdfDocument(new PdfPage(300, 200, new PdfMargins(20, 20, 20, 20)),
            List.of(new PdfParagraph(List.of(new PdfTextRun("Java 17 café €", new PdfTextStyle("regular", 11))))));
        byte[] output = new PdfEngine().render(document, List.of(new PdfFont("regular", font)));
        try (var pdf = Loader.loadPDF(output)) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("Java 17 café €"));
        }
    }
}

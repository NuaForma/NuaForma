package dev.nuaforma.pdf;

import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaPdfEngineTest {
    @Test
    void javaCallerCanRenderCyrillicWithLanguageMetadata() throws IOException {
        var style = new PdfTextStyle("regular", 11, new PdfColor(0, 0, 0), "uk-UA");
        var paragraph = new PdfParagraph(List.of(new PdfTextRun("Київ, Україна", style)));
        byte[] font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        try (var pdf = Loader.loadPDF(new PdfEngine().render(new PdfDocument(new PdfPage(300, 200, new PdfMargins(20, 20, 20, 20)), List.of(paragraph)), List.of(new PdfFont("regular", font))))) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("Київ, Україна"));
        }
    }

    @Test
    void javaCallerCanRenderAutomaticLineSpacing() throws IOException {
        var format = new PdfParagraphFormat(PdfAlignment.LEFT, 0, 0, 0, null, true, false, false, false, false, null, null, new PdfAutomaticLineSpacing(1.5f));
        var p = new PdfParagraph(List.of(new PdfTextRun("Java automatic spacing", new PdfTextStyle("regular", 11))), 0, 0, format);
        byte[] font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        try (var pdf = Loader.loadPDF(new PdfEngine().render(new PdfDocument(new PdfPage(300, 200, new PdfMargins(20, 20, 20, 20)), List.of(p), new PdfFlow()), List.of(new PdfFont("regular", font))))) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("Java automatic spacing"));
        }
    }

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
    void javaCallerCanRequestRightToLeftParagraphs() throws IOException {
        byte[] font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        var format = new PdfParagraphFormat(PdfAlignment.RIGHT, 0, 0, 0, 18f, false,
            false, false, false, false, null, null, null, PdfTextDirection.RIGHT_TO_LEFT);
        var paragraph = new PdfParagraph(List.of(new PdfTextRun("שלום עולם 123", new PdfTextStyle("regular", 11))), 0, 0, format);
        var document = new PdfDocument(new PdfPage(300, 200, new PdfMargins(20, 20, 20, 20)), List.of(paragraph));
        try (var pdf = Loader.loadPDF(new PdfEngine().render(document, List.of(new PdfFont("regular", font))))) {
            assertEquals("שלום עולם 123", new PDFTextStripper().getText(pdf).trim());
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

    @Test
    void javaCallerCanRenderResolvedPageSequences() throws IOException {
        byte[] font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        var blocks = List.<PdfBlock>of(new PdfParagraph(List.of(new PdfTextRun("Sequence", new PdfTextStyle("regular", 11)))));
        var margins = new PdfMargins(20, 20, 20, 20);
        var document = new PdfDocument(List.of(new PdfPageSequence(new PdfPage(300, 200, margins), blocks),
            new PdfPageSequence(new PdfPage(200, 300, margins), blocks)), 2);
        try (var pdf = Loader.loadPDF(new PdfEngine().render(document, List.of(new PdfFont("regular", font))))) {
            assertEquals(2, pdf.getNumberOfPages());
            assertEquals(300f, pdf.getPage(0).getMediaBox().getWidth());
            assertEquals(200f, pdf.getPage(1).getMediaBox().getWidth());
        }
    }

    @Test
    void javaCallerCanSelectFirstPageBandsAndExplicitBlankVariants() throws IOException {
        byte[] font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        var paragraph = new PdfParagraph(List.of(new PdfTextRun("FIRST JAVA", new PdfTextStyle("regular", 10))), 0, 0,
            new PdfParagraphFormat(PdfAlignment.LEFT, 0, 0, 0, 14f));
        var first = new PdfPageBands(new PdfPageBand(8, 20, List.of(paragraph)), null);
        var flow = new PdfFlow(null, null, 32, first, new PdfPageBands());
        var sequence = new PdfPageSequence(new PdfPage(240, 180, new PdfMargins(30, 20, 30, 20)), List.of(paragraph), flow);
        try (var pdf = Loader.loadPDF(new PdfEngine().render(new PdfDocument(List.of(sequence, sequence)), List.of(new PdfFont("regular", font))))) {
            assertEquals(2, pdf.getNumberOfPages());
            assertEquals(4, new PDFTextStripper().getText(pdf).split("FIRST JAVA", -1).length - 1);
        }
    }

    @Test
    void javaCallerCanUseFinalDocumentPageCount() throws IOException {
        byte[] font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        var style = new PdfTextStyle("regular", 10);
        var format = new PdfParagraphFormat(PdfAlignment.LEFT, 0, 0, 0, 14f);
        var field = new PdfParagraph(List.of(new PdfPageNumber(style), new PdfTextRun("/", style), new PdfPageCount(style)), 0, 0, format);
        var body = new PdfParagraph(List.of(new PdfTextRun("Body", style)), 0, 0, format);
        var flow = new PdfFlow(new PdfPageBand(8, 20, List.of(field)));
        var sequence = new PdfPageSequence(new PdfPage(240, 180, new PdfMargins(30, 20, 30, 20)), List.of(body), flow);
        try (var pdf = Loader.loadPDF(new PdfEngine().render(new PdfDocument(List.of(sequence, sequence)), List.of(new PdfFont("regular", font))))) {
            var text = new PDFTextStripper().getText(pdf);
            assertEquals(2, pdf.getNumberOfPages());
            org.junit.jupiter.api.Assertions.assertTrue(text.contains("1/2") && text.contains("2/2"));
        }
    }

    @Test
    void javaCallerCanRestartNumberingAndReadSequenceTotals() throws IOException {
        byte[] font;
        try (var input = getClass().getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            font = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        var style = new PdfTextStyle("regular", 10);
        var format = new PdfParagraphFormat(PdfAlignment.LEFT, 0, 0, 0, 14f);
        var field = new PdfParagraph(List.of(new PdfPageNumber(style), new PdfTextRun("/", style), new PdfSequencePageCount(style), new PdfTextRun("/", style), new PdfPageCount(style)), 0, 0, format);
        var body = new PdfParagraph(List.of(new PdfTextRun("Body", style)), 0, 0, format);
        var page = new PdfPage(240, 180, new PdfMargins(30, 20, 30, 20));
        var flow = new PdfFlow(new PdfPageBand(8, 20, List.of(field)));
        var sequences = List.of(new PdfPageSequence(page, List.of(body), flow, new PdfPageNumbering(0, true)), new PdfPageSequence(page, List.of(body), flow));
        try (var pdf = Loader.loadPDF(new PdfEngine().render(new PdfDocument(sequences), List.of(new PdfFont("regular", font))))) {
            assertEquals(2, pdf.getNumberOfPages());
            var text = new PDFTextStripper().getText(pdf);
            org.junit.jupiter.api.Assertions.assertTrue(text.contains("0/1/2") && text.contains("1/1/2"));
        }
    }
}

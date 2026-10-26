package dev.nuaforma.examples;

import dev.nuaforma.pdf.PdfDocument;
import dev.nuaforma.pdf.PdfPage;
import dev.nuaforma.pdf.PdfMargins;
import dev.nuaforma.pdf.PdfParagraph;
import dev.nuaforma.pdf.PdfTextRun;
import dev.nuaforma.pdf.PdfTextStyle;
import dev.nuaforma.pdf.PdfEngine;
import dev.nuaforma.pdf.PdfFont;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PdfEngineExample {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Provide a TrueType font and an output PDF path");
        var paragraph = new PdfParagraph(List.of(new PdfTextRun("Independent Java 17: café € office affinity", new PdfTextStyle("regular", 11))));
        var document = new PdfDocument(new PdfPage(595.28f, 841.89f, new PdfMargins(56.7f, 56.7f, 56.7f, 56.7f)), List.of(paragraph));
        byte[] bytes = new PdfEngine().render(document, List.of(new PdfFont("regular", Files.readAllBytes(Path.of(args[0])))));
        if (bytes.length < 100 || bytes[0] != '%' || bytes[1] != 'P') throw new IllegalStateException("PDF not generated");
        Files.write(Path.of(args[1]), bytes);
        System.out.println("Independent PDF engine consumer passed");
    }
}

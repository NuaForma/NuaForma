package dev.nuaforma.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import org.docx4j.Docx4J;
import org.docx4j.convert.out.FOSettings;
import org.docx4j.fonts.IdentityPlusMapper;
import org.docx4j.fonts.PhysicalFonts;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;

/** Research-only converter for trusted synthetic fixtures, not an engine adapter or sandbox. */
public final class FopProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("input.docx output.pdf [font files...]");
        PhysicalFonts.setRegex("a^");
        for (int i = 2; i < args.length; i++) {
            PhysicalFonts.addPhysicalFont(Path.of(args[i]).toUri());
        }
        var document = WordprocessingMLPackage.load(Path.of(args[0]).toFile());
        try {
            document.setFontMapper(new IdentityPlusMapper());
            var settings = new FOSettings(document);
            Path output = Path.of(args[1]);
            Files.createDirectories(output.toAbsolutePath().getParent());
            settings.setFoDumpFile(Path.of(args[1] + ".fo").toFile());
            try (var stream = Files.newOutputStream(output)) {
                int exporter = Boolean.getBoolean("evaluation.visitor")
                        ? Docx4J.FLAG_EXPORT_PREFER_NONXSL : Docx4J.FLAG_EXPORT_PREFER_XSL;
                Docx4J.toFO(settings, stream, exporter);
            }
        } finally {
            if (document.getMainDocumentPart().getFontTablePart() != null) {
                document.getMainDocumentPart().getFontTablePart().deleteEmbeddedFontTempFiles();
            }
        }
    }
}

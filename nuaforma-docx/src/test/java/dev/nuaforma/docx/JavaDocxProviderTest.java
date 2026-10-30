package dev.nuaforma.docx;

import dev.nuaforma.contracts.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaDocxProviderTest {
    @Test
    void builtInRichTextWorksThroughExistingJavaFacadeAndMetadataConstructors() throws Exception {
        var parts = new java.util.LinkedHashMap<>(SyntheticDocx.INSTANCE.parts("Java {details | richText(\"rtf\")}"));
        var output = new java.io.ByteArrayOutputStream();
        var requirements = new GenerationRequirements(new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.DOCX));
        var request = new GenerationRequest(new java.io.ByteArrayInputStream(SyntheticDocx.INSTANCE.zip(parts)), requirements,
            java.util.Map.of("details", "{\\rtf1\\b inserted\\b0\\par Second}", "customer", java.util.Map.of("name", "Ada"), "reference", "NF001"));
        var provider = new DocxProvider();
        provider.generate(request, output, new GenerationContext());
        var document = new String(SyntheticDocx.INSTANCE.unzip(output.toByteArray()).get("word/document.xml"), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(document.contains(">inserted</w:t>"));
        assertTrue(document.contains(">Second</w:t>"));
        var definition = provider.contentPipes().stream().filter(pipe -> pipe.getName().equals("richText")).findFirst().orElseThrow();
        assertTrue(definition.getMissingAsNull());
        assertEquals(java.util.Set.of("application/rtf"), definition.getContentTypes());
        assertFalse(new ContentPipeDefinition("legacy", "Legacy Java constructor").getMissingAsNull());
    }
    @Test
    void usedFontSelectionUsesExplicitJavaCatalogWithoutDeclarations(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        try (var source = getClass().getResourceAsStream("/fonts/LibreBarcode39-Regular.ttf")) {
            java.nio.file.Files.copy(source, root.resolve("opaque.ttf"));
        }
        var catalog = DeploymentFonts.load(java.util.List.of(root), null);
        DocumentProvider provider = new DocxProvider(catalog);
        var parts = new java.util.LinkedHashMap<>(SyntheticDocx.INSTANCE.parts("Java font selection"));
        parts.replaceAll((name, bytes) -> name.endsWith(".xml") ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
            .replace("Arial", "Libre Barcode 39").replace("<w:b/>", "").replace("<w:i/>", "")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8) : bytes);
        var requirements = new GenerationRequirements(new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.DOCX,
            java.util.Map.of("fontMode", "embed", "fontSelection", "used")));
        assertEquals(AssessmentStatus.SUPPORTED, provider.assess(requirements).getStatus());
        var output = new java.io.ByteArrayOutputStream();
        provider.generate(new GenerationRequest(new java.io.ByteArrayInputStream(SyntheticDocx.INSTANCE.zip(parts)), requirements,
            java.util.Map.of("customer", java.util.Map.of("name", "Ada"), "reference", "NF001")), output, new GenerationContext());
        assertEquals(1, SyntheticDocx.INSTANCE.unzip(output.toByteArray()).keySet().stream().filter(name -> name.endsWith(".odttf")).count());
        assertEquals(FontOrigin.BUNDLED, catalog.getCatalog().getFonts().get(0).getOrigin());
    }

    @Test
    void standardImageIsAvailableThroughJavaContracts() throws Exception {
        var pixels = new java.awt.image.BufferedImage(8, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var picture = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(pixels, "png", picture));
        var data = java.util.Map.of("logo", java.util.Base64.getEncoder().encodeToString(picture.toByteArray()),
            "customer", java.util.Map.of("name", "Ada"), "reference", "NF001");
        var template = SyntheticDocx.INSTANCE.zip(SyntheticDocx.INSTANCE.parts("Logo {logo | image(20, 10)}"));
        var output = new java.io.ByteArrayOutputStream();
        DocumentProvider provider = new DocxProvider();
        provider.generate(new GenerationRequest(new java.io.ByteArrayInputStream(template), new GenerationRequirements(
            new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.DOCX)), data), output, new GenerationContext());
        assertTrue(SyntheticDocx.INSTANCE.unzip(output.toByteArray()).keySet().stream().anyMatch(name -> name.startsWith("word/media/nuaforma-image-")));
        assertEquals("image", ((ContentPipeProvider) provider).contentPipes().get(0).getName());
    }

    @Test
    void providerIsUsableThroughJavaContracts() {
        DocumentProvider provider = new DocxProvider();
        GenerationRequirements requirements = new GenerationRequirements(
            new FormatRequest(DocumentFormat.DOCX), new FormatRequest(DocumentFormat.DOCX));
        assertEquals(AssessmentStatus.SUPPORTED, provider.assess(requirements).getStatus());
        assertTrue(provider.availability().supports(DocumentFormat.DOCX, DocumentFormat.DOCX));
    }
}

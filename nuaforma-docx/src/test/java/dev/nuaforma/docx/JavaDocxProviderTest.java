package dev.nuaforma.docx;

import dev.nuaforma.contracts.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaDocxProviderTest {
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

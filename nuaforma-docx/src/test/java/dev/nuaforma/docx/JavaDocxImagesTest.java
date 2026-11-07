package dev.nuaforma.docx;

import dev.nuaforma.contracts.GenerationContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaDocxImagesTest {

    @Test
    void normalizeIsUsableThroughBothStaticJavaOverloads() throws Exception {
        var image = new java.awt.image.BufferedImage(24, 12, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var source = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, "jpeg", source));
        var bytes = source.toByteArray();
        var normal = DocxImages.normalize(bytes);
        var explicit = DocxImages.normalize(bytes, new GenerationContext());
        assertArrayEquals(normal, explicit);
        assertNotSame(normal, explicit);
        assertArrayEquals(new byte[]{-119, 80, 78, 71, 13, 10, 26, 10}, java.util.Arrays.copyOf(normal, 8));
        var decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(normal));
        assertEquals(24, decoded.getWidth());
        assertEquals(12, decoded.getHeight());
    }
}

package dev.nuaforma.docx;

import dev.nuaforma.contracts.GenerationException;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class JavaFacadeTest {
    @Test
    void useFacadeWithJavaAndTryWithResources() throws Exception {
        byte[] input = SyntheticDocx.INSTANCE.zip(SyntheticDocx.INSTANCE.parts("Java reference"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DocxDocument document = DocxDocuments.open(new ByteArrayInputStream(input))) {
            document.writeTo(output);
        }
        assertTrue(output.size() > 0);
        assertThrows(GenerationException.class, () -> DocxDocuments.open(new ByteArrayInputStream(new byte[0])));
    }
}

package dev.nuaforma.contracts;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaVectorContractTest {

    @Test
    void java17CanConstructVectorsWithoutKotlinDefaultsOrNewDependencies() {
        VectorPath path = new VectorPath(List.of(new VectorPoint(100, 100), new VectorPoint(900, 900)));
        ContentResult result = new ContentResult.Vector(List.of(path));
        assertEquals(70, ((ContentResult.Vector) result).getStrokeWidth());
        assertFalse(path.getClosed());
        assertEquals("unchanged", new ContentResult.Text("unchanged").getText());
        assertNotNull(ContentResult.Empty.INSTANCE);
    }
}

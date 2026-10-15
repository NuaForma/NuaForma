package dev.nuaforma.template;

import dev.nuaforma.contracts.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JavaContentTemplatesTest {
    @Test void additiveContentFacadeIsUsableFromJava17() throws GenerationException {
        ContentPipe pipe = new ContentPipe() {
            public ContentPipeDefinition getDefinition() {
                return new ContentPipeDefinition("company", "Synthetic company content");
            }
            public ContentResult apply(Object value, List<? extends Object> arguments, ContentPipeContext context) throws GenerationException {
                context.checkActive();
                return new ContentResult.Text((String) value);
            }
        };
        ContentTemplateSession session = ContentTemplates.open(Map.of("name", "Ada"), List.of(pipe));
        ContentTemplateFragment fragment = session.expandContent("{name | company()}").get(0);
        assertEquals("Ada", ((ContentResult.Text) fragment.getContent()).getText());
        assertThrows(GenerationException.class, () -> session.expand("{name | company()}"));
    }
}

package dev.nuaforma.template;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaTemplateTest {
    @Test
    void javaCanCompileAndRenderWithoutKotlinSpecificArguments() throws Exception {
        CompiledTemplate template = Templates.compile("Hello {name}{#if active}!{/if}");
        assertEquals("Hello Ada!", template.render(Map.of("name", "Ada", "active", true)));
        assertEquals(17, Runtime.version().feature());
    }

    @Test
    void runtimeDoesNotPullInCoreDocxOrServer() {
        for (String name : new String[] {"dev.nuaforma.core.ProviderRegistry", "org.docx4j.wml.Document", "dev.nuaforma.docx.DocxDocuments"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(name));
        }
    }
}
